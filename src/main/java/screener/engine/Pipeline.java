package screener.engine;

import screener.Config;
import screener.model.Bar;
import screener.model.DisclosureCheck;
import screener.model.FlowDay;
import screener.model.Fundamentals;
import screener.model.Security;
import screener.model.SourceStatus;
import screener.provider.MarketDataProvider;
import screener.store.Db;
import screener.util.Log;
import screener.util.Num;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 한 시장 실행: 상장목록 자동 수집 → 일봉 → 신규 후보 필터 → 수급·재무 → MAS/OIS(독립) → 공시 필수 검증
 * → 상태·알림·추적 → MSI(독립) 저장. 단계별 실패는 격리되어 요약에 기록된다.
 */
public final class Pipeline {
    private final MarketDataProvider p;
    private final Db db;
    private final Config cfg;
    private LocalDate effectiveAsOf;

    public Pipeline(MarketDataProvider p, Db db, Config cfg) {
        this.p = p;
        this.db = db;
        this.cfg = cfg;
    }

    public Map<String, Object> run() throws Exception {
        String m = p.market();
        long runId = db.startRun(m, p.isDemo() ? "demo" : "live");
        Map<String, Object> sum = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        sum.put("notes", notes);
        String status = "OK";
        String error = null;
        LocalDate asOf = null;
        StringBuilder dayNote = new StringBuilder();
        try {
            Log.info("[" + m + "] 실행 시작 (run " + runId + ")");
            asOf = p.completedTradingDay(dayNote);
            sum.put("completedDay", asOf.toString());
            Log.info("[" + m + "] 완료 거래일 " + asOf + " — " + dayNote);
            status = execute(runId, asOf, sum, notes, dayNote);
            if (effectiveAsOf != null) asOf = effectiveAsOf;
        } catch (Exception e) {
            status = "FAILED";
            error = Log.redact(e.getClass().getSimpleName() + ": " + e.getMessage());
            Log.error("[" + m + "] 실행 실패: " + error);
        }
        // 카카오 보고(선택): 계좌 평가가 기준일에 확정됐을 때만 정상 보고. 실패해도 실행 결과에 영향 없음.
        if (!p.isDemo() && asOf != null) {
            try {
                String acc = m + "-" + ("KR".equals(m) ? "KRW" : "USD");
                boolean eqOk = db.count("SELECT COUNT(*) FROM paper_equity_daily WHERE account=? AND date=?", acc, asOf.toString()) > 0;
                boolean confirmed = !"FAILED".equals(status) && eqOk && !(sum.get("paper") instanceof Map && ((Map<?, ?>) sum.get("paper")).containsKey("error"));
                String why = "FAILED".equals(status) ? "실행 실패: " + error : !eqOk ? "기준일 계좌 평가 없음" : "모의매매 처리 오류";
                sum.put("kakao", new screener.report.KakaoReporter(cfg, db).report(m, asOf.toString(), confirmed, why));
            } catch (Exception e) {
                Log.warn("카카오 보고 생략(격리): " + Log.redact(String.valueOf(e.getMessage())));
            }
        }
        Map<String, Object> srcs = new LinkedHashMap<>();
        boolean anySourceFail = false;
        for (SourceStatus s : p.sources().all().values()) {
            srcs.put(s.name, s.toMap());
            if (s.failures > 0 || s.disabled) anySourceFail = true;
        }
        if ("OK".equals(status) && anySourceFail) status = "PARTIAL";
        db.finishRun(runId, status, asOf == null ? null : asOf.toString(), dayNote.toString(), sum, srcs, error);
        sum.put("runId", runId);
        sum.put("status", status);
        Log.info("[" + m + "] 실행 종료: " + status);
        return sum;
    }

    private String execute(long runId, LocalDate asOfIn, Map<String, Object> sum, List<String> notes, StringBuilder dayNote) throws Exception {
        String m = p.market();
        // 1) 상장 목록
        List<Security> all = p.listUniverse(asOfIn);
        if (all.isEmpty()) throw new IllegalStateException("상장 목록 수집 결과 0건");
        sum.put("listingsTotal", all.size());
        Map<String, Integer> typeEx = new TreeMap<>();
        List<Security> common = new ArrayList<>();
        int mcapUnknown = 0;
        for (Security s : all) {
            String ex = s.typeExclusion();
            if (ex != null) { typeEx.merge(ex.startsWith("상태 위험") ? "상태 위험(관리·정지·재무상태)" : ex.startsWith("동일 기업") ? "동일 기업 다른 클래스" : ex, 1, Integer::sum); continue; }
            common.add(s);
            if (s.marketCap == null) mcapUnknown++;
        }
        sum.put("typeExcluded", typeEx);
        sum.put("commonStocks", common.size());
        sum.put("mcapUnknown", mcapUnknown);
        common.sort(Comparator.comparing((Security s) -> s.marketCap == null ? -1 : s.marketCap).reversed());

        Set<String> trackedMas = new LinkedHashSet<>(db.statesIn("MAS", m, Classifier.MAS_ACTIVE));
        Set<String> trackedOis = new LinkedHashSet<>(db.statesIn("OIS", m, Classifier.OIS_ACTIVE));
        for (Map<String, Object> t : db.trackingsFor("MAS", m, asOfIn.toString())) trackedMas.add((String) t.get("symbol"));
        for (Map<String, Object> t : db.trackingsFor("OIS", m, asOfIn.toString())) trackedOis.add((String) t.get("symbol"));
        Set<String> paperSyms = PaperTrader.heldOrPending(db, m); // 모의 계좌 보유·체결 대기 종목은 항상 수집·평가
        trackedOis.addAll(paperSyms);

        int oisN = "KR".equals(m) ? cfg.krOisTop : cfg.usOisTop;
        double thr = Rules.masMcap(m);
        List<Security> masPre = common.stream().filter(s -> s.marketCap != null && s.marketCap >= thr * 0.85).collect(Collectors.toList());
        int prelimOisN = (int) Math.round(oisN * ("US".equals(m) ? 1.15 : 1.0)); // 미국: 예비 시총 오차 대비 여유분 후 재정렬
        List<Security> oisPre = common.stream().filter(s -> s.marketCap != null).limit(prelimOisN).collect(Collectors.toList());
        if (cfg.limit > 0) {
            masPre = masPre.stream().limit(cfg.limit).collect(Collectors.toList());
            oisPre = oisPre.stream().limit(cfg.limit).collect(Collectors.toList());
            notes.add("검증용 종목 수 상한 적용: --limit " + cfg.limit + " (시총 상위부터) — 전체 스캔 아님");
            sum.put("limitApplied", cfg.limit);
        }
        Map<String, Security> bySym = new HashMap<>();
        for (Security s : all) bySym.put(s.symbol, s);
        LinkedHashMap<String, Security> load = new LinkedHashMap<>();
        for (Security s : masPre) load.put(s.symbol, s);
        for (Security s : oisPre) load.put(s.symbol, s);
        for (String t : trackedMas) if (bySym.containsKey(t)) load.put(t, bySym.get(t));
        for (String t : trackedOis) if (bySym.containsKey(t)) load.put(t, bySym.get(t));
        List<Security> loadList = new ArrayList<>(load.values());
        sum.put("masPrefilter", masPre.size());
        sum.put("oisPrefilter", oisPre.size());
        sum.put("scanRequested", loadList.size());

        // 2) 일봉
        Map<String, List<Bar>> bars = p.bars(loadList, asOfIn);
        int barsFailed = loadList.size() - bars.size();
        sum.put("barsOk", bars.size());
        sum.put("barsFailed", barsFailed);
        List<String> failedSyms = loadList.stream().map(s -> s.symbol).filter(x -> !bars.containsKey(x)).limit(30).collect(Collectors.toList());
        sum.put("barsFailedSample", failedSyms);
        // 완료 거래일 2차 확인: 수집 종목의 80% 이상이 확정 일봉을 가진 최신 거래일(공급원이 당일 봉을 미확정으로 주는 경우 대비)
        LocalDate adj = effectiveDay(bars, asOfIn, p.indexBars(asOfIn));
        final LocalDate asOf = adj;
        if (!adj.equals(asOfIn)) {
            String why = "지수 기준 완료일 " + asOfIn + "에 확정 일봉이 있는 종목이 80% 미만 — 종목 자료 기준 완료일 " + adj + "로 조정(미확정 봉은 추정하지 않음)";
            notes.add(why);
            dayNote.append("; ").append(why);
            sum.put("completedDay", asOf.toString());
            sum.put("completedDayIndex", dayNote.toString());
        }
        effectiveAsOf = asOf;
        String as = asOf.toString();
        p.finalizeMarketCap(loadList, bars, asOf);

        MarketContext ctx = new MarketContext(m, asOf, p);
        for (Security s : loadList) {
            ctx.secs.put(s.symbol, s);
            List<Bar> b = bars.get(s.symbol);
            if (b != null) ctx.bars.put(s.symbol, Indicators.upTo(b, asOf));
        }
        Map<String, List<Bar>> idx = p.indexBars(asOf);
        idx.forEach((k, v) -> ctx.index.put(k, Indicators.upTo(v, asOf)));
        if (idx.isEmpty()) notes.add("벤치마크 지수 수집 실패 — 상대강도·MSI 일부 미평가");

        // OIS 유니버스 확정(최종 시총 기준 상위 N)
        List<Security> oisSet = loadList.stream().filter(s -> s.typeExclusion() == null && s.marketCap != null && ctx.bars.containsKey(s.symbol))
                .sorted(Comparator.comparing((Security s) -> s.marketCap).reversed()).limit(cfg.limit > 0 ? cfg.limit : oisN).collect(Collectors.toList());
        for (String t : trackedOis) if (ctx.secs.containsKey(t) && oisSet.stream().noneMatch(s -> s.symbol.equals(t))) oisSet.add(ctx.secs.get(t));
        sum.put("oisUniverse", oisSet.size());

        // 3) MAS 신규 후보 필터
        Map<String, Integer> failReasons = new TreeMap<>();
        int passed = 0, unconfirmed = 0;
        List<Security> masSet = new ArrayList<>();
        for (Security s : masPre) {
            if (!ctx.bars.containsKey(s.symbol)) continue;
            ScoreCard pf = MasScorer.prefilter(s, ctx);
            if (pf.filterPassed) { passed++; masSet.add(s); }
            else if (pf.filterUnconfirmed) { unconfirmed++; masSet.add(s); }
            else {
                for (Map<String, Object> f : pf.filters) if ("FAIL".equals(f.get("result"))) failReasons.merge((String) f.get("name"), 1, Integer::sum);
                if (trackedMas.contains(s.symbol)) masSet.add(s);
            }
        }
        for (String t : trackedMas) if (ctx.secs.containsKey(t) && masSet.stream().noneMatch(s -> s.symbol.equals(t))) masSet.add(ctx.secs.get(t));
        Map<String, Object> mf = new LinkedHashMap<>();
        mf.put("evaluated", masPre.stream().filter(s -> ctx.bars.containsKey(s.symbol)).count());
        mf.put("passed", passed);
        mf.put("unconfirmed", unconfirmed);
        mf.put("failedBy", failReasons);
        sum.put("masFilter", mf);

        // 4) 수급(한국) — 매집 대상 + MSI 집계용 상위 K
        List<Security> flowAgg = new ArrayList<>();
        if (p.flowsSupported()) {
            List<Security> need = new ArrayList<>(masSet);
            for (Security s : oisSet) if (!need.contains(s)) need.add(s);
            Map<String, List<FlowDay>> fl = p.flows(need, asOf, 70);
            flowAgg = oisSet.stream().limit(cfg.krFlowAggTop).collect(Collectors.toList());
            Map<String, List<FlowDay>> fl2 = p.flows(flowAgg, asOf, 130);
            fl.forEach((k, v) -> ctx.flows.put(k, cut(v, asOf)));
            fl2.forEach((k, v) -> { if (!ctx.flows.containsKey(k) || ctx.flows.get(k).size() < v.size()) ctx.flows.put(k, cut(v, asOf)); });
            sum.put("flowsOk", ctx.flows.size());
            sum.put("flowsRequested", need.size());
        } else {
            sum.put("flows", "미지원(" + p.flowSourceLabel() + ")");
        }

        // 5) 업종·재무
        List<Security> scoreSet = new ArrayList<>(masSet);
        for (Security s : oisSet) if (!scoreSet.contains(s)) scoreSet.add(s);
        p.enrichSectors(scoreSet);
        Map<String, Fundamentals> fund = p.fundamentals(scoreSet, asOf);
        ctx.fundamentals.putAll(fund);
        sum.put("fundamentalsOk", fund.size());
        sum.put("fundamentalsRequested", scoreSet.size());
        sum.put("sectorsOk", scoreSet.stream().filter(s -> s.sector != null).count());
        ctx.buildPeers();

        // 6) MSI (독립)
        Map<String, Object> msi = computeMsi(ctx, oisSet, flowAgg, asOf, notes);
        db.upsertMsi(runId, msi);
        if (msi.get("score") != null) {
            ctx.msiScore = ((Number) msi.get("score")).doubleValue();
            ctx.msiCh5 = msi.get("ch5") == null ? null : ((Number) msi.get("ch5")).doubleValue();
        }
        msiAlerts(msi, as);
        Map<String, Object> ms = new LinkedHashMap<>();
        ms.put("score", msi.get("score")); ms.put("band", msi.get("band")); ms.put("W", msi.get("W")); ms.put("provisional", msi.get("provisional"));
        sum.put("msi", ms);

        // 7) MAS / OIS 점수(독립 계산)
        Map<String, DisclosureCheck> dcache = new HashMap<>();
        int disOk = 0, disFail = 0;
        Map<String, Integer> masStatus = new TreeMap<>(), oisStatus = new TreeMap<>();
        int newAlerts = 0;
        for (Security s : masSet) {
            ScoreCard c = MasScorer.score(s, ctx);
            String base = db.baselineState("MAS", m, s.symbol, as);
            boolean tracked = base != null && Classifier.MAS_ACTIVE.contains(base) && !"PENDING".equals(base) || db.trackingAsOf("MAS", m, s.symbol, as) != null;
            DisclosureCheck dc = null;
            if (c.rangeHigh() >= 50 || tracked) {
                dc = dcache.computeIfAbsent(s.symbol, k -> p.disclosures(s, asOf));
                if (dc.ok) disOk++; else disFail++;
                if (dc.indirectNote != null) c.indirect.add(Map.of("name", "공시 간접 자료", "value", dc.indirectNote, "scored", false));
                c.indirect.addAll(p.indirectEvidence(s, ctx.bars.getOrDefault(s.symbol, List.of()), asOf));
            }
            Classifier.finalizeMas(c, RiskRules.financial(ctx.fundamentals.get(s.symbol), asOf), dc, base, tracked, p.fundamentalsSourceLabel());
            newAlerts += persist(runId, c, base, s, ctx);
            masStatus.merge(c.status, 1, Integer::sum);
        }
        Map<String, ScoreCard> oisCards = new LinkedHashMap<>();
        for (Security s : oisSet) {
            ScoreCard c = OisScorer.score(s, ctx);
            oisCards.put(s.symbol, c);
            String base = db.baselineState("OIS", m, s.symbol, as);
            DisclosureCheck dc = null;
            if (c.rangeHigh() >= 50 || (base != null && Classifier.OIS_ACTIVE.contains(base))) {
                boolean cached = dcache.containsKey(s.symbol);
                dc = dcache.computeIfAbsent(s.symbol, k -> p.disclosures(s, asOf));
                if (!cached) { if (dc.ok) disOk++; else disFail++; }
            }
            Classifier.finalizeOis(c, RiskRules.financial(ctx.fundamentals.get(s.symbol), asOf), dc, base, p.fundamentalsSourceLabel());
            c.info.put("declineType", OisScorer.classifyDecline(c, s, ctx, dc));
            c.info.put("scenario", OisScorer.scenario(s, ctx, c));
            c.info.put("msiRef", ctx.msiScore == null ? "MSI 미산출" : "시장 " + msi.get("name") + " " + msi.get("score") + " (" + msi.get("band") + ") — 참고용, 합산하지 않음");
            c.info.put("takeProfitRule", "보유 원가 입력 없음: +10% 1차·+15% 추가 차익실현은 추적(모의, 탐지 종가 기준)에서 도달 여부로만 기록. MSI 76/80/90 이상은 시장 단위 준비/축소/적극 검토.");
            newAlerts += persist(runId, c, base, s, ctx);
            oisStatus.merge(c.status, 1, Integer::sum);
        }
        sum.put("masScored", masSet.size());
        sum.put("oisScored", oisSet.size());
        sum.put("masStatus", masStatus);
        sum.put("oisStatus", oisStatus);
        sum.put("disclosuresOk", disOk);
        sum.put("disclosuresFailed", disFail);

        // 8) 추적
        int cps = Tracker.update(db, "MAS", ctx, notes) + Tracker.update(db, "OIS", ctx, notes);
        sum.put("checkpointsAdded", cps);

        // 9) 모의 자동매매(역발상 OIS 후보, 다음 거래일 시가 체결). 실패해도 분석 저장은 유지.
        if ("true".equals(db.setting("paper_enabled", "true"))) {
            try {
                Map<String, Object> ps = new PaperTrader(db, p, ctx, runId).run(oisCards, msi);
                sum.put("paper", ps);
                PaperTrader.logSummary(m, ps);
            } catch (Exception e) {
                sum.put("paper", Map.of("error", Log.redact(String.valueOf(e))));
                notes.add("모의매매 처리 실패: " + Log.redact(String.valueOf(e)));
                Log.error("[" + m + "] 모의매매 실패: " + Log.redact(String.valueOf(e)));
            }
        }
        sum.put("newAlerts", newAlerts);
        prune(m);
        boolean partial = barsFailed > 0 || disFail > 0 || (p.flowsSupported() && ctx.flows.isEmpty());
        return partial ? "PARTIAL" : "OK";
    }

    /** 후보일부터 거슬러 올라가며 종목 확정 봉 보유 비율 ≥80% 인 첫 거래일. 일봉이 없으면 후보일 그대로. */
    static LocalDate effectiveDay(Map<String, List<Bar>> bars, LocalDate cand, Map<String, List<Bar>> idx) {
        if (bars.isEmpty()) return cand;
        java.util.TreeSet<LocalDate> days = new java.util.TreeSet<>();
        for (List<Bar> l : idx.values()) for (Bar b : l) if (!b.date().isAfter(cand)) days.add(b.date());
        days.add(cand);
        int checked = 0;
        for (LocalDate d : days.descendingSet()) {
            int have = 0;
            for (List<Bar> l : bars.values()) for (int i = l.size() - 1; i >= 0; i--) { LocalDate x = l.get(i).date(); if (x.isAfter(d)) continue; if (x.equals(d)) have++; break; }
            if (have >= bars.size() * 0.8) return d;
            if (++checked >= 5) break;
        }
        return cand;
    }

    private static List<FlowDay> cut(List<FlowDay> l, LocalDate d) {
        List<FlowDay> o = new ArrayList<>();
        for (FlowDay f : l) if (!f.date().isAfter(d)) o.add(f);
        return o;
    }

    /** 점수·상태·전환·알림·추적 등록. 반환: 새 알림 수. */
    private int persist(long runId, ScoreCard c, String base, Security s, MarketContext ctx) throws Exception {
        db.upsertScore(runId, c);
        String as = ctx.asOf.toString();
        boolean changed = db.setState(c.model, c.market, c.symbol, c.name, base, c.status, as, c.S(), c.statusReason);
        int alerts = 0;
        Set<String> reg = "MAS".equals(c.model) ? Set.of("WATCH", "EARLY", "STRONG", "HIGH", "VERY_HIGH") : Set.of("OBSERVE", "INTEREST", "PRIORITY");
        List<Bar> b = ctx.bars.getOrDefault(s.symbol, List.of());
        if (reg.contains(c.status) && db.trackingAsOf(c.model, c.market, c.symbol, as) == null && !b.isEmpty() && b.get(b.size() - 1).date().equals(ctx.asOf)) {
            List<Bar> bm = ctx.bench(s);
            Double bc = Tracker.closeOn(bm, ctx.asOf);
            db.createTracking(c.model, c.market, c.symbol, c.name, as, b.get(b.size() - 1).close(), c.S(), c.status, p.benchmarkKey(s), bc);
        }
        if (!changed) return 0;
        String type = null, title = null;
        if ("MAS".equals(c.model)) {
            int rn = Rules.masRank(c.status), rb = Rules.masRank(base);
            if ("EARLY".equals(c.status) && rn > rb) type = "초기 매집 징후";
            else if (rn >= 3 && rn > rb) type = "강화 신호 및 고득점";
            else if ("INVALIDATED".equals(c.status)) type = "무효화";
            else if ("BLOCKED".equals(c.status) && base != null && Classifier.MAS_ACTIVE.contains(base)) type = "위험 신호";
            if (type != null) title = "[" + c.market + " 매집 의심] " + c.name + "(" + c.symbol + ") " + c.statusLabel + " — 확인 " + c.S() + "점, 신뢰도 " + c.confidence;
        } else {
            int rn = Classifier.oisRank(c.status), rb = Classifier.oisRank(base);
            if (rn >= 2 && rn > rb) type = "역발상 매수 후보";
            else if ("EXCLUDED_RISK".equals(c.status) && base != null && Classifier.OIS_ACTIVE.contains(base)) type = "기업 펀더멘털 위험";
            if (type != null) title = "[" + c.market + " 역발상] " + c.name + "(" + c.symbol + ") " + c.statusLabel + " — OIS 확인 " + c.S() + "점";
        }
        if (type == null) return 0;
        String from10 = ctx.asOf.minusDays(10).toString();
        if ("무효화".equals(type) && db.count("SELECT COUNT(*) FROM alerts WHERE model='MAS' AND market=? AND symbol=? AND type IN ('초기 매집 징후','강화 신호 및 고득점') AND as_of>=? AND as_of<?",
                c.market, c.symbol, ctx.asOf.minusDays(120).toString(), as) == 0) return 0; // 알림 단계에 이른 적 없는 관찰 후보의 무효화는 전환 기록만
        if (!type.equals("무효화") && !type.contains("위험") && db.count("SELECT COUNT(*) FROM alerts WHERE model=? AND market=? AND symbol=? AND type=? AND as_of>=? AND as_of<?",
                c.model, c.market, c.symbol, type, from10, as) > 0) return 0; // 같은 유형 10일 내 재알림 억제
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("market", c.market);
        body.put("asOf", as);
        body.put("price", c.info.get("price"));
        body.put("ret20", c.info.get("ret20"));
        body.put("ret60", c.info.get("ret60"));
        body.put("score", c.toMap().get("display"));
        body.put("status", c.statusLabel + " (" + c.gradeQualifier + ")");
        body.put("confidence", c.confidence);
        body.put("pros", c.pros);
        body.put("cons", c.cons);
        body.put("missing", c.missing);
        body.put("nextChecks", c.nextChecks);
        body.put("from", Rules.label(base));
        body.put("reason", c.statusReason);
        if (c.info.containsKey("declineType")) body.put("declineType", c.info.get("declineType"));
        body.put("disclaimer", "매집·기회 '의심/추정' 신호이며 확정 판단이 아님. 분석·모의 검증 전용 — 실제 주문 없음.");
        if (db.addAlert(c.model, c.market, c.symbol, c.name, as, type, title, body)) alerts++;
        return alerts;
    }

    private void msiAlerts(Map<String, Object> msi, String as) throws Exception {
        if (msi.get("score") == null) return;
        int sc = ((Number) msi.get("rounded")).intValue();
        Map<String, Object> prev = db.msiBefore(p.market(), as);
        Integer ps = prev == null || prev.get("score") == null ? null : (int) Math.round(((Number) prev.get("score")).doubleValue());
        String dyn = (String) msi.get("dynamic");
        String pdyn = prev == null ? null : (String) prev.get("dynamic");
        String name = (String) msi.get("name");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("score", msi.get("score")); body.put("band", msi.get("band")); body.put("ch1", msi.get("ch1")); body.put("ch5", msi.get("ch5"));
        body.put("dynamic", dyn); body.put("confidence", msi.get("confidence")); body.put("provisional", msi.get("provisionalReason"));
        body.put("action", msi.get("action"));
        String from10 = LocalDate.parse(as).minusDays(10).toString();
        java.util.function.Predicate<String> recent = t -> {
            try { return db.count("SELECT COUNT(*) FROM alerts WHERE model='MSI' AND market=? AND type=? AND as_of>=? AND as_of<?", p.market(), t, from10, as) > 0; }
            catch (Exception e) { return false; }
        };
        if (sc <= 15 && (ps == null || ps > 15) && !recent.test("극단적 공포 발생"))
            db.addAlert("MSI", p.market(), p.market(), name, as, "극단적 공포 발생", "[" + name + "] 극단적 공포 감지 — " + msi.get("score") + "점", body);
        if (dyn != null && (dyn.equals("공포 완화") || dyn.equals("투매 진정")) && !dyn.equals(pdyn) && !recent.test("공포 완화"))
            db.addAlert("MSI", p.market(), p.market(), name, as, "공포 완화", "[" + name + "] " + dyn + " 감지 — " + msi.get("score") + "점", body);
        for (int t : new int[]{76, 80, 90}) {
            if (sc >= t && (ps == null || ps < t) && !recent.test("시장 과열 " + t))
                db.addAlert("MSI", p.market(), p.market(), name, as, "시장 과열 " + t, "[" + name + "] 시장 과열 " + t + "점 이상 — 차익실현 " + (t == 76 ? "준비" : t == 80 ? "비중 축소 검토" : "적극 검토"), body);
        }
    }

    private Map<String, Object> computeMsi(MarketContext ctx, List<Security> oisSet, List<Security> flowAgg, LocalDate asOf, List<String> notes) {
        String m = ctx.market;
        String primary = "KR".equals(m) ? "KOSPI" : "SPX";
        List<Bar> pb = ctx.index.getOrDefault(primary, List.of());
        List<LocalDate> cal = pb.stream().map(Bar::date).collect(Collectors.toList());
        MsiCalculator mc = new MsiCalculator(m, cal);
        String isrc = p.priceSourceLabel();
        Map<String, List<Bar>> sent = p.sentimentSeries(asOf);
        Map<String, List<Bar>> uni = new HashMap<>();
        for (Security s : oisSet) if (ctx.bars.containsKey(s.symbol)) uni.put(s.symbol, ctx.bars.get(s.symbol));
        int minCount = Math.max(10, (int) (uni.size() * 0.5));
        if ("US".equals(m)) {
            for (String k : new String[]{"SPX", "NDX"}) {
                List<Bar> b = ctx.index.get(k);
                if (b == null) { mc.note(k + " 지수 미확보"); continue; }
                mc.add("가격·변동성", "지수 수익률", k + " 5일 수익률", +1, false, "Yahoo Finance 지수 일봉", MsiCalculator.returns(b, 5));
                mc.add("가격·변동성", "지수 수익률", k + " 20일 수익률", +1, false, "Yahoo Finance 지수 일봉", MsiCalculator.returns(b, 20));
            }
            if (sent.containsKey("VIX")) mc.add("가격·변동성", "변동성", "VIX 종가", -1, false, "Cboe VIX_History.csv", MsiCalculator.closes(sent.get("VIX")));
            else mc.note("VIX 미확보");
            if (sent.containsKey("VIX") && sent.containsKey("VIX3M"))
                mc.add("수급·파생", "변동성 기간구조", "VIX/VIX3M 비율", -1, true, "Cboe (풋콜·ETF 유출입 대체, app-9)", MsiCalculator.ratio(sent.get("VIX"), sent.get("VIX3M")));
            else mc.note("VIX3M 미확보 — 수급·파생 영역 미평가");
            mc.note("ETF 자금 유출입·신뢰 가능한 풋/콜 비율: 무료 공식 공급원 미확보(Cboe 일별 통계 403) — 대체 지표 사용");
            mc.note("CNN Fear & Greed: 자동 요청 차단(HTTP 418) — 교차검증 미사용");
        } else {
            for (String k : new String[]{"KOSPI", "KOSDAQ"}) {
                List<Bar> b = ctx.index.get(k);
                if (b == null) { mc.note(k + " 지수 미확보"); continue; }
                mc.add("가격·변동성", "지수 수익률", k + " 5일 수익률", +1, false, isrc, MsiCalculator.returns(b, 5));
                mc.add("가격·변동성", "지수 수익률", k + " 20일 수익률", +1, false, isrc, MsiCalculator.returns(b, 20));
            }
            if (ctx.index.containsKey("KOSPI"))
                mc.add("가격·변동성", "변동성", "KOSPI 20일 실현변동성", -1, true, "지수 일봉 계산(VKOSPI 대체, app-9)", MsiCalculator.realizedVol(ctx.index.get("KOSPI"), 20));
            mc.note("VKOSPI: KRX OpenAPI 파생지수 서비스 401(미신청) — 실현변동성으로 대체");
            if (!flowAgg.isEmpty()) {
                Map<String, List<FlowDay>> fa = new HashMap<>();
                for (Security s : flowAgg) if (ctx.flows.containsKey(s.symbol)) fa.put(s.symbol, ctx.flows.get(s.symbol));
                int minS = Math.max(5, (int) (flowAgg.size() * 0.6));
                mc.add("수급·파생", "외국인", "외국인 5일 순매수/거래대금(상위 " + flowAgg.size() + "종목 합산)", +1, true, p.flowSourceLabel() + " (시장 수급 대체, app-9)", MsiCalculator.flowRatio(fa, ctx.bars, 0, minS));
                mc.add("수급·파생", "기관", "기관 5일 순매수/거래대금(상위 " + flowAgg.size() + "종목 합산)", +1, true, p.flowSourceLabel() + " (시장 수급 대체, app-9)", MsiCalculator.flowRatio(fa, ctx.bars, 1, minS));
            }
        }
        mc.add("시장 확산도", "상승 비율", "상승 종목 비율(5일 평균, 시총 상위 " + uni.size() + "종목)", +1, false, isrc, MsiCalculator.advanceRatio(uni, minCount));
        mc.add("시장 확산도", "신고가-신저가", "52주 신고가 비중−신저가 비중(시총 상위 " + uni.size() + "종목)", +1, false, isrc, MsiCalculator.highLow(uni, minCount));
        if (!p.isDemo()) {
            LocalDate prev = null;
            for (LocalDate d : cal) if (d.isBefore(asOf)) prev = d;
            Map<LocalDate, Double> news = NewsSentiment.ensure(db, p.newsSource(), m, prev, asOf, notes);
            mc.addDirect("뉴스·커뮤니티", "뉴스", "뉴스 심리(NAVER API HUB, 언론사 평균)", true,
                    NewsSentiment.VERSION + " — 커뮤니티 미수집, 키워드 규칙(풍자·반어 판별 불가)", news);
            mc.note("커뮤니티 게시물: 미수집(디시인사이드·4chan 기본 제외 원칙 유지) — 뉴스만으로 영역 산출 시 대체 표시");
        } else {
            mc.note("뉴스·커뮤니티 심리: 데모 모드 미수집 — 미평가");
        }
        mc.note("확산도는 현재 시총 상위 종목으로 과거를 계산하므로 생존 편향 가능");
        return mc.compute(asOf);
    }

    /** 오래된 기준일의 비후보 점수 상세 JSON 정리(요약 행은 유지). */
    private void prune(String market) {
        try {
            List<Map<String, Object>> d = db.query("SELECT DISTINCT as_of FROM scores WHERE market=? ORDER BY as_of DESC LIMIT 1 OFFSET 10", market);
            if (d.isEmpty()) return;
            db.conn().createStatement().executeUpdate("UPDATE scores SET card_json=NULL WHERE market='" + market + "' AND as_of<='" + d.get(0).get("as_of")
                    + "' AND status IN ('NONE','HOLD')");
        } catch (Exception e) {
            Log.warn("정리 실패: " + e.getMessage());
        }
    }

    public static String fmt(String market, Double v) { return Num.money(market, v); }
}
