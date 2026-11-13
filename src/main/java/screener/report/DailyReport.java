package screener.report;

import screener.engine.Indicators;
import screener.engine.MarketContext;
import screener.engine.Rules;
import screener.engine.ScoreCard;
import screener.model.Bar;
import screener.model.FlowDay;
import screener.model.Security;
import screener.provider.MarketDataProvider;
import screener.store.Db;
import screener.util.Json;
import screener.util.Num;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 시장별 일일 보고서(원본 02 §11). 이번 실행의 계산값과 DB 기록만 사용한다.
 * 확보하지 못한 지표는 '미평가', 대체·근사는 실제 지표명을 함께 적는다. 상태 변화 알림 후보(§10 A·C·D, 보유 위험, MAS 무효화)도 산출한다.
 */
public final class DailyReport {
    private DailyReport() { }

    public record Event(String type, String subject, String text) { }

    public record Result(String text, Map<String, Object> content, String summary, List<Event> events) { }

    public static Result build(Db db, MarketContext ctx, MarketDataProvider p, Map<String, Object> msi, Map<String, ScoreCard> ois,
                               List<Security> flowAgg, String checkedAt) throws Exception {
        String m = ctx.market, d = ctx.asOf.toString();
        boolean kr = "KR".equals(m);
        Map<String, Object> c = new LinkedHashMap<>();
        List<String> t = new ArrayList<>();
        t.add("■ " + (kr ? "한국" : "미국") + " 일일 보고서 · 기준일 " + d + " · 확인 " + checkedAt + " KST");

        // 1) MSI
        Double ms = msi == null || msi.get("score") == null ? null : ((Number) msi.get("score")).doubleValue();
        String msiLine = ms == null ? "미산출(" + (msi == null ? "" : msi.get("reason")) + ")"
                : String.format("%.1f %s%s · 전일 %s · 5일 %s · 신뢰도 %s", ms, msi.get("band"), Boolean.TRUE.equals(msi.get("provisional")) ? "(잠정)" : "",
                sign(msi.get("ch1")), sign(msi.get("ch5")), msi.get("confidence"));
        c.put("msi", msiLine);
        t.add("1. " + (kr ? "KR" : "US") + "-MSI: " + msiLine);

        // 2) 지수 추세
        List<String> idx = new ArrayList<>();
        for (String k : kr ? new String[]{"KOSPI", "KOSDAQ"} : new String[]{"SPX", "NDX"}) {
            List<Bar> b = ctx.index.get(k);
            if (b == null || b.size() < 21) { idx.add(label(k) + " 미평가(지수 자료 부족)"); continue; }
            Bar x = b.get(b.size() - 1);
            Double ma = Indicators.sma(b, 20);
            idx.add(String.format("%s %,.2f (1일 %s, 5일 %s, 20일 %s, 20일선 %s)", label(k), x.close(), Num.pct(Indicators.ret(b, 1)), Num.pct(Indicators.ret(b, 5)),
                    Num.pct(Indicators.ret(b, 20)), ma != null && x.close() >= ma ? "위" : "아래"));
        }
        c.put("indices", idx);
        t.add("2. 지수 추세: " + String.join(" / ", idx));

        // 3) 변동성
        MarketDataProvider.VolObs v = p.volatility(ctx.asOf);
        String vol;
        if (v != null && v.close() != null) vol = String.format("%s %.2f (전일 대비 %s)%s", v.name(), v.close(), v.change() == null ? "-" : String.format("%+.2f", v.change()),
                v.note() == null ? "" : " — " + v.note());
        else {
            vol = (kr ? "VKOSPI" : "VIX") + " 미평가(" + (v == null ? "공급원 미지원" : v.note()) + ")";
            if (kr && ctx.index.containsKey("KOSPI")) {
                Double rv = Indicators.realizedVol(ctx.index.get("KOSPI"), 20);
                if (rv != null) vol += String.format(" · 대체: KOSPI 20일 실현변동성(연율) %.1f%%", rv * Math.sqrt(252) * 100);
            }
        }
        c.put("volatility", vol);
        t.add("3. 변동성: " + vol);

        // 4) 상승·하락 종목 비율
        String breadth = kr ? krBreadth(db, ctx.asOf) : null;
        if (breadth == null) {
            int up = 0, dn = 0, n = 0;
            for (String s : ois.keySet()) {
                List<Bar> b = ctx.bars.get(s);
                if (b == null || b.size() < 2 || !b.get(b.size() - 1).date().equals(ctx.asOf)) continue;
                n++;
                double r = b.get(b.size() - 1).close() / b.get(b.size() - 2).close() - 1;
                if (r > 0) up++; else if (r < 0) dn++;
            }
            breadth = n == 0 ? "미평가(일봉 없음)" : String.format("상승 %d · 하락 %d (상승비율 %.1f%%) — 시총 상위 %d종목 기준(전체 시장 아님)", up, dn, 100.0 * up / n, n);
        }
        c.put("breadth", breadth);
        t.add("4. 상승·하락: " + breadth);

        // 5) 수급
        String flow;
        if (kr) {
            double f = 0, in = 0;
            int n = 0;
            for (Security s : flowAgg == null ? List.<Security>of() : flowAgg)
                for (FlowDay fd : ctx.flows.getOrDefault(s.symbol, List.of()))
                    if (fd.date().equals(ctx.asOf)) { if (fd.foreignQty() != null) f += fd.foreignQty() * fd.close(); if (fd.instQty() != null) in += fd.instQty() * fd.close(); n++; }
            flow = "시장 전체 투자자 수급 미평가(무료 확정치 없음)" + (n == 0 ? "" : String.format(" · 근사: 시총 상위 %d종목 외국인 %s·기관 %s (Naver 순매수 수량×종가)", n, eok(f), eok(in)));
        } else flow = "미평가(미국은 투자자별 순매수 직접 자료 없음)";
        c.put("flows", flow);
        t.add("5. 수급: " + flow);

        // 6) 뉴스 심리
        String news;
        List<Map<String, Object>> nw = db.query("SELECT date, score, status, note, outlets FROM news_daily WHERE market=? AND date<=? ORDER BY date DESC LIMIT 2", m, d);
        if (!nw.isEmpty() && d.equals(nw.get(0).get("date")) && "OK".equals(nw.get(0).get("status"))) {
            double s0 = ((Number) nw.get(0).get("score")).doubleValue();
            Double s1 = nw.size() > 1 && nw.get(1).get("score") != null ? ((Number) nw.get(1).get("score")).doubleValue() : null;
            news = String.format("%.1f (%s, 독립 출처 %s곳, 전회 대비 %s) — 뉴스만, 커뮤니티 미수집", s0, Rules.msiBand((int) Math.round(s0)), nw.get(0).get("outlets"), s1 == null ? "-" : String.format("%+.1f", s0 - s1));
        } else news = "미평가(" + (nw.isEmpty() || !d.equals(nw.get(0).get("date")) ? "기준일 수집 기록 없음" : nw.get(0).get("note")) + ")";
        c.put("news", news);
        t.add("6. 뉴스 심리: " + news);

        // 7) 후보 TOP 10 (중대 위험 제외, 확인 점수 순)
        List<ScoreCard> top = ois.values().stream().filter(x -> !"EXCLUDED_RISK".equals(x.status)).sorted(Comparator.comparingDouble(ScoreCard::S).reversed()).limit(10).collect(Collectors.toList());
        List<String> tl = new ArrayList<>();
        int i = 1;
        for (ScoreCard x : top) {
            tl.add(String.format("%d. %s(%s) OIS 확인 %.1f/%.0f(커버리지 %.0f%%) %s · 60일 낙폭 %s · 20일 %s · 거래량 %s · RSI %s · %s", i++, x.name, x.symbol, x.S(), x.W(), x.coverage(),
                    x.statusLabel, Num.pct((Double) x.info.get("drawdown60")), Num.pct((Double) x.info.get("ret20")),
                    x.info.get("volMultiple") == null ? "-" : String.format("%.1f배", (Double) x.info.get("volMultiple")),
                    x.info.get("rsi14") == null ? "-" : String.format("%.0f", (Double) x.info.get("rsi14")), shortDecline((String) x.info.get("declineType"))));
        }
        c.put("top10", tl);
        t.add("7. 후보 TOP 10" + (tl.isEmpty() ? ": 없음" : ""));
        t.addAll(tl);

        // 8) 모의 보유 종목 위험
        String acc = m + "-" + (kr ? "KRW" : "USD");
        List<Map<String, Object>> risks = new ArrayList<>();
        List<String> rl = new ArrayList<>();
        for (Map<String, Object> pos : db.query("SELECT symbol, name, qty, avg_cost FROM paper_positions WHERE account=? AND qty>0 ORDER BY symbol", acc)) {
            String sym = (String) pos.get("symbol");
            ScoreCard x = ois.get(sym);
            List<Bar> b = ctx.bars.getOrDefault(sym, List.of());
            Double ret = b.isEmpty() ? null : b.get(b.size() - 1).close() / ((Number) pos.get("avg_cost")).doubleValue() - 1;
            String level, why;
            if (x == null) { level = "UNKNOWN"; why = "OIS 미산출 — 위험 점검 불가"; }
            else if (x.blocked || "EXCLUDED_RISK".equals(x.status)) { level = "SEVERE"; why = x.blockReason != null ? x.blockReason : "중대 위험 공시·재무"; }
            else if (x.items.stream().anyMatch(it -> "trend".equals(it.key) && it.grade != null && it.grade == 0.0) || x.cons.stream().anyMatch(s -> s.contains("위험 공시"))) { level = "WARN"; why = "실적 악화 또는 검토 필요 공시"; }
            else { level = "OK"; why = "중대 위험 미확인"; }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("symbol", sym); r.put("name", pos.get("name")); r.put("level", level); r.put("reason", why); r.put("ret", ret);
            risks.add(r);
            rl.add(String.format("%s %s 평가손익 %s — %s", pos.get("name"), level.equals("SEVERE") ? "[중대]" : level.equals("WARN") ? "[주의]" : level.equals("UNKNOWN") ? "[미확인]" : "[양호]", Num.pct(ret), why));
        }
        c.put("holdingRisks", risks);
        t.add("8. 모의 보유 종목 위험: " + (rl.isEmpty() ? "보유 없음" : ""));
        t.addAll(rl);

        // 9) 모의매매 결과
        List<Map<String, Object>> fills = db.query("SELECT side, name, stage, qty, realized_pnl FROM paper_fills WHERE account=? AND fill_date=? ORDER BY id", acc, d);
        long buys = fills.stream().filter(f -> "BUY".equals(f.get("side"))).count(), sells = fills.size() - buys;
        double realized = fills.stream().filter(f -> "SELL".equals(f.get("side"))).mapToDouble(f -> ((Number) f.get("realized_pnl")).doubleValue()).sum();
        int unf = db.count("SELECT COUNT(*) FROM paper_orders WHERE account=? AND status='UNFILLED' AND target_date=?", acc, d);
        int def = db.count("SELECT COUNT(*) FROM paper_signals WHERE account=? AND decision='DEFER' AND signal_date=?", acc, d);
        int next = db.count("SELECT COUNT(*) FROM paper_orders WHERE account=? AND status='PENDING' AND signal_date=?", acc, d);
        List<Map<String, Object>> eq = db.query("SELECT equity, deposits_cum FROM paper_equity_daily WHERE account=? AND date<=? ORDER BY date DESC LIMIT 2", acc, d);
        String pnl = "-";
        if (!eq.isEmpty()) {
            double e0 = ((Number) eq.get(0).get("equity")).doubleValue(), dep = ((Number) eq.get(0).get("deposits_cum")).doubleValue();
            pnl = String.format("일 %s · 누적 %s · 총자산 %s", eq.size() > 1 ? Num.money(m, e0 - ((Number) eq.get(1).get("equity")).doubleValue()) : "-", Num.pct(e0 / dep - 1), Num.money(m, e0));
        }
        String paper = String.format("체결 %d건(매수 %d·매도 %d%s) · 미체결 %d · 보류 %d · 다음 시가 주문 %d · %s", fills.size(), buys, sells,
                sells > 0 ? ", 실현 " + Num.money(m, realized) : "", unf, def, next, pnl);
        c.put("paper", paper);
        t.add("9. 모의매매: " + paper);

        // 10) 전략 판단(규칙) + 신규 후보·초기 징후·강화 묶음
        long stage1 = ois.values().stream().filter(x -> stageMet(x, 1)).count();
        String judge = judge(ms, stage1);
        List<Map<String, Object>> tr = db.query("SELECT model, name, to_state FROM transitions WHERE market=? AND as_of=? AND to_state IN ('EARLY','STRONG','HIGH','VERY_HIGH','INTEREST','PRIORITY','WATCH','OBSERVE') ORDER BY model, to_state", m, d);
        Map<String, List<String>> bundle = new LinkedHashMap<>();
        for (Map<String, Object> r : tr) bundle.computeIfAbsent(r.get("model") + " " + Rules.label((String) r.get("to_state")), k -> new ArrayList<>()).add((String) r.get("name"));
        String bundled = bundle.isEmpty() ? "없음" : bundle.entrySet().stream().map(e -> e.getKey() + " " + e.getValue().size() + "(" + String.join(", ", e.getValue().subList(0, Math.min(3, e.getValue().size()))) + ")").collect(Collectors.joining(" / "));
        c.put("judgement", judge);
        c.put("newSignals", bundled);
        t.add("10. 전략 판단(규칙): " + judge);
        t.add("    오늘 신규·초기 징후·강화: " + bundled);
        t.add("※ 분석·모의 검증 전용. 점수는 확률·수익률이 아님. 규칙 " + Rules.MSI_VERSION.split(" ")[0] + "/" + Rules.OIS_VERSION.split(" ")[0] + "/" + Rules.MAS_VERSION.split(" ")[0]);

        // 카카오 요약(1통, 200자 이내)
        String vShort = v != null && v.close() != null ? (kr ? "VKOSPI " : "VIX ") + String.format("%.1f", v.close()) : (kr ? "VKOSPI 미평가" : "VIX 미평가");
        Bar ix = ctx.index.containsKey(kr ? "KOSPI" : "SPX") && ctx.index.get(kr ? "KOSPI" : "SPX").size() > 1 ? ctx.index.get(kr ? "KOSPI" : "SPX").get(ctx.index.get(kr ? "KOSPI" : "SPX").size() - 1) : null;
        StringBuilder s = new StringBuilder("[모의매매·" + m + "] " + d + " 마감\n");
        s.append("MSI ").append(ms == null ? "미산출" : String.format("%.1f %s(전일 %s)", ms, msi.get("band"), sign(msi.get("ch1")))).append("\n");
        s.append(kr ? "코스피 " : "S&P500 ").append(ix == null ? "-" : Num.pct(Indicators.ret(ctx.index.get(kr ? "KOSPI" : "SPX"), 1))).append(" · ").append(vShort).append("\n");
        s.append(String.format("체결 %d(매수%d·매도%d) 미체결 %d 보류 %d\n", fills.size(), buys, sells, unf, def));
        s.append(pnl.replace(" · 총자산 ", " · 총 ")).append("\n");
        if (!bundle.isEmpty()) s.append("신규·강화: ").append(bundle.entrySet().stream().map(e -> e.getKey() + e.getValue().size()).collect(Collectors.joining(", "))).append("\n");
        s.append("판단: ").append(judge.length() > 30 ? judge.substring(0, 30) : judge).append("\n확인 ").append(checkedAt.substring(11, 16));
        String summary = KakaoReporter.fit(s.toString());

        // 상태 변화 알림(§10 A·C·D, 보유 중대 위험, MAS 무효화) — 상태가 바뀐 경우에만
        List<Event> ev = new ArrayList<>();
        String head = "[모의매매·" + m + " 알림] ";
        String tail = "\n기준일 " + d + " · 확인 " + checkedAt.substring(11, 16);
        for (Map<String, Object> a : db.query("SELECT type, symbol, name FROM alerts WHERE market=? AND as_of=? AND (type IN ('극단적 공포 발생','공포 완화','시장 과열 76') OR (model='MAS' AND type='무효화')) ORDER BY id", m, d)) {
            String ty = (String) a.get("type");
            if ("무효화".equals(ty)) ev.add(new Event("MAS_INVALIDATED", (String) a.get("symbol"), KakaoReporter.fit(head + "MAS 후보 무효화: " + a.get("name") + "(" + a.get("symbol") + ")\n사유: 대시보드 상태 전환 참조" + tail)));
            else {
                String type = ty.startsWith("극단") ? "EXTREME_FEAR" : ty.startsWith("공포") ? "FEAR_RELIEF" : "OVERHEAT";
                String act = type.equals("EXTREME_FEAR") ? "대응: 우량 성장주 1차(20%) 분할 진입 후보 점검(1차 조건 충족 " + stage1 + "종목)"
                        : type.equals("FEAR_RELIEF") ? "대응: 3차(심리 반전) 조건·반전 신호 점검" : "대응: 보유 종목 차익실현 준비(+10%·+15% 기준 병행)";
                ev.add(new Event(type, m, KakaoReporter.fit(head + ty + "\n" + (kr ? "KR" : "US") + "-MSI " + (ms == null ? "-" : String.format("%.1f(전일 %s)", ms, sign(msi.get("ch1")))) + " · " + vShort + "\n" + act + tail)));
            }
        }
        for (Map<String, Object> r : risks) {
            String sym = (String) r.get("symbol");
            List<Map<String, Object>> prev = db.query("SELECT state FROM event_state WHERE market=? AND kind='HOLDING_RISK' AND subject=?", m, sym);
            String before = prev.isEmpty() ? "OK" : (String) prev.get(0).get("state");
            boolean severe = "SEVERE".equals(r.get("level"));
            if (severe && !"SEVERE".equals(before))
                ev.add(new Event("HOLDING_RISK", sym, KakaoReporter.fit(head + "모의 보유 종목 중대 위험: " + r.get("name") + "\n" + r.get("reason") + " · 평가손익 " + Num.pct((Double) r.get("ret"))
                        + "\n추가 매수 중단, 가설 훼손 시 전량 청산 규칙 적용" + tail)));
            try (var ps = db.prepare("INSERT OR REPLACE INTO event_state(market, kind, subject, state, as_of) VALUES(?, 'HOLDING_RISK', ?, ?, ?)")) {
                ps.setString(1, m); ps.setString(2, sym); ps.setString(3, severe ? "SEVERE" : "OK"); ps.setString(4, d);
                ps.executeUpdate();
            }
        }
        c.put("events", ev.stream().map(Event::type).collect(Collectors.toList()));
        String text = String.join("\n", t);
        try (var ps = db.prepare("INSERT OR REPLACE INTO reports(market, report_type, period_key, checked_at, text, content_json, created_at) VALUES(?, 'DAILY', ?, ?, ?, ?, ?)")) {
            ps.setString(1, m); ps.setString(2, d); ps.setString(3, checkedAt); ps.setString(4, text); ps.setString(5, Json.write(c));
            ps.setString(6, java.time.LocalDateTime.now().withNano(0).toString());
            ps.executeUpdate();
        }
        return new Result(text, c, summary, ev);
    }

    /** 한국 전 종목 상승·하락(KRX 일별매매정보, 직전 개장일 종가 대비). */
    static String krBreadth(Db db, LocalDate asOf) throws Exception {
        List<String> ds = db.krxOpenDates(asOf.toString(), 2);
        if (ds.size() < 2 || !ds.get(0).equals(asOf.toString())) return null;
        List<Map<String, Object>> r = db.query("SELECT SUM(CASE WHEN a.close>b.close THEN 1 ELSE 0 END) up, SUM(CASE WHEN a.close<b.close THEN 1 ELSE 0 END) dn, COUNT(*) n"
                + " FROM krx_daily a JOIN krx_daily b ON b.code=a.code AND b.date=? WHERE a.date=? AND a.close>0 AND b.close>0", ds.get(1), ds.get(0));
        if (r.isEmpty() || r.get(0).get("n") == null || ((Number) r.get(0).get("n")).intValue() == 0) return null;
        int up = ((Number) r.get(0).get("up")).intValue(), dn = ((Number) r.get(0).get("dn")).intValue(), n = ((Number) r.get(0).get("n")).intValue();
        return String.format("상승 %d · 하락 %d · 보합 %d (상승비율 %.1f%%) — KOSPI·KOSDAQ 전 종목(KRX)", up, dn, n - up - dn, 100.0 * up / n);
    }

    static String judge(Double ms, long stage1) {
        if (ms == null) return "MSI 미산출 — 신규 진입 판단 보류";
        int r = (int) Math.round(ms);
        if (r <= 15) return "극단적 공포 — 1차(20%) 조건 충족 " + stage1 + "종목, 다음 시가 분할 진입 검토";
        if (r <= 30) return "공포 — 극단적 공포(15 이하) 전까지 1차 진입 대기, 우량 후보 관찰";
        if (r <= 45) return "약한 공포 — 추가 투매·심리 악화 관망";
        if (r <= 60) return "중립 — 신규 진입 대기";
        if (r <= 75) return "낙관 — 보유 종목 점검";
        return "탐욕(" + r + ") — 차익실현 " + (r >= 90 ? "적극 검토" : r >= 80 ? "비중 축소 검토" : "준비");
    }

    @SuppressWarnings("unchecked")
    static boolean stageMet(ScoreCard c, int k) {
        Object sc = c.info.get("scenario");
        if (!(sc instanceof List) || ((List<Object>) sc).size() < k) return false;
        return Boolean.TRUE.equals(((Map<String, Object>) ((List<Object>) sc).get(k - 1)).get("met"));
    }

    static String sign(Object v) { return v instanceof Number ? String.format("%+.1f", ((Number) v).doubleValue()) : "-"; }

    static String label(String k) {
        switch (k) { case "KOSPI": return "코스피"; case "KOSDAQ": return "코스닥"; case "SPX": return "S&P500"; case "NDX": return "나스닥100"; default: return k; }
    }

    static String eok(double won) { return String.format("%+,.0f억", won / 1e8); }

    static String shortDecline(String s) { return s == null ? "-" : s.contains("(") ? s.substring(0, s.indexOf('(')).trim() : s; }
}
