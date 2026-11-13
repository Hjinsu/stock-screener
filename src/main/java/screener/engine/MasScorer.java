package screener.engine;

import screener.model.Bar;
import screener.model.FlowDay;
import screener.model.Fundamentals;
import screener.model.Security;
import screener.util.Num;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 매집 의심 점수(MAS). 원본 01의 6개 영역 배점과 신규 후보 필터, skill accumulation.md 초기 판정 규칙을 적용한다.
 * 미평가 항목은 grade=null 로 남기며 0점으로 채우지 않는다.
 */
public final class MasScorer {
    private MasScorer() { }

    public static final String A_FLOW = "투자자 수급";
    public static final String A_VOL = "거래량·거래대금";
    public static final String A_PRICE = "가격 안정·흡수";
    public static final String A_RS = "상대강도";
    public static final String A_VOLA = "변동성 축소";
    public static final String A_FIN = "재무·경쟁력";

    /** 수급·재무 수집 전에 신규 등록 필터만 평가(호출량 절감). */
    public static ScoreCard prefilter(Security s, MarketContext ctx) {
        ScoreCard c = new ScoreCard("MAS", Rules.MAS_VERSION, s.market, s.symbol);
        List<Bar> b = ctx.bars.getOrDefault(s.symbol, List.of());
        boolean delayed = b.isEmpty() || !b.get(b.size() - 1).date().equals(ctx.asOf);
        filters(c, s, b, ctx, delayed);
        return c;
    }

    public static ScoreCard score(Security s, MarketContext ctx) {
        ScoreCard c = new ScoreCard("MAS", Rules.MAS_VERSION, s.market, s.symbol);
        c.name = s.name;
        c.asOf = ctx.asOf.toString();
        List<Bar> b = ctx.bars.getOrDefault(s.symbol, List.of());
        String priceSrc = ctx.provider.priceSourceLabel();
        String lastDate = b.isEmpty() ? null : b.get(b.size() - 1).date().toString();
        boolean delayed = lastDate == null || !lastDate.equals(ctx.asOf.toString());
        boolean approxValue = !b.isEmpty() && b.get(b.size() - 1).valueApprox();

        info(c, s, b, ctx);
        filters(c, s, b, ctx, delayed);

        // ① 투자자 수급 25
        flows(c, s, b, ctx);

        // ② 거래량·거래대금 20
        Double volR = Indicators.recentVsPrior(b, 5, 20, 0);
        Double valR = Indicators.recentVsPrior(b, 5, 20, 1);
        ScoreItem iVol = c.add(new ScoreItem(A_VOL, "vol", "20일 평균 대비 거래량 증가", 6, "최근5일 평균/직전20일 평균 ≥1.2 충족, >1 부분"));
        ScoreItem iVal = c.add(new ScoreItem(A_VOL, "val", "20일 평균 대비 거래대금 증가", 6, "최근5일 평균/직전20일 평균 ≥1.2 충족, >1 부분"));
        ScoreItem iUd = c.add(new ScoreItem(A_VOL, "updown", "상승일·하락일 거래량 차이", 5, "최근20일 상승일 평균거래량/하락일 평균거래량 ≥1.2 충족, >1 부분"));
        ScoreItem iPer = c.add(new ScoreItem(A_VOL, "persist", "거래량 증가 지속성", 3, "최근5일 중 직전20일 대비 1.2배 이상인 날 ≥3 충족, 2 부분"));
        ratioGrade(iVol, volR, "배율 ");
        ratioGrade(iVal, valR, "배율 ");
        if (approxValue) { iVal.substitute = true; iVal.note = "거래대금=종가×거래량 근사(app-2)"; }
        Double ud = upDownVolumeRatio(b, 20);
        if (ud == null) iUd.unevaluated("최근20일 상승일 또는 하락일 부재");
        else ratioGrade(iUd, ud, "상승/하락 거래량 배율 ");
        if (b.size() >= 25) {
            int cnt = 0;
            for (int k = b.size() - 5; k < b.size(); k++) {
                Double m = Indicators.volMultipleAt(b, k, 20);
                if (m != null && m >= 1.2) cnt++;
            }
            iPer.grade(cnt >= 3 ? 1.0 : cnt == 2 ? 0.5 : 0.0, cnt + "일/5일");
        } else iPer.unevaluated("일봉 25개 미만");
        if (ud != null && ud < 1.0) {
            for (ScoreItem it : new ScoreItem[]{iVol, iVal, iPer}) {
                if (it.grade != null && it.grade > 0) {
                    it.grade = 0.0;
                    it.note = (it.note == null ? "" : it.note + "; ") + "하락일 거래 집중 — 매집 근거로 해석하지 않음(app-4)";
                }
            }
            c.cons.add("최근 20일 거래량이 하락일에 집중(상승/하락 배율 " + Num.f2(ud) + ") — 물량 출회 가능성 우선 검토");
        }

        // ③ 가격 안정·흡수 20
        ScoreItem iRange = c.add(new ScoreItem(A_PRICE, "range", "횡보 구간 유지", 5, "최근20일 (최고가-최저가)/최저가 ≤15% 충족, ≤20% 부분"));
        ScoreItem iLow = c.add(new ScoreItem(A_PRICE, "lowdef", "저점 방어", 5, "최근10일 최저가 ≥ 직전10일 최저가 충족, 2% 이내 하회 부분"));
        ScoreItem iRec = c.add(new ScoreItem(A_PRICE, "recover", "장중 하락 후 종가 회복", 5, "장중 전일종가 하회 & 종가가 당일 범위 상단25% — 최근20일 ≥3회 충족, 2회 부분"));
        ScoreItem iAsym = c.add(new ScoreItem(A_PRICE, "asym", "상승·하락 변동 비대칭", 5, "상승일 절대수익률 중앙값/하락일 중앙값 ≥1.2 충족, >1 부분"));
        if (b.size() >= 20) {
            double hi = 0, lo = Double.MAX_VALUE;
            for (int k = b.size() - 20; k < b.size(); k++) { hi = Math.max(hi, b.get(k).high()); lo = Math.min(lo, b.get(k).low()); }
            double rg = lo > 0 ? (hi - lo) / lo : Double.NaN;
            iRange.grade(rg <= 0.15 ? 1.0 : rg <= 0.20 ? 0.5 : 0.0, "고저폭 " + String.format("%.1f%%", rg * 100));
            double l1 = Double.MAX_VALUE, l0 = Double.MAX_VALUE;
            for (int k = b.size() - 10; k < b.size(); k++) l1 = Math.min(l1, b.get(k).low());
            for (int k = b.size() - 20; k < b.size() - 10; k++) l0 = Math.min(l0, b.get(k).low());
            iLow.grade(l1 >= l0 ? 1.0 : l1 >= l0 * 0.98 ? 0.5 : 0.0, String.format("최근10일 저점/직전10일 저점 = %.3f", l1 / l0));
            if (valR != null && valR <= 1.0 && rg <= 0.15) c.cons.add("거래대금 증가 없는 횡보 — 단순 거래 부진 가능성");
        } else { iRange.unevaluated("일봉 20개 미만"); iLow.unevaluated("일봉 20개 미만"); }
        if (b.size() >= 21) {
            int cnt = 0;
            for (int k = b.size() - 20; k < b.size(); k++) {
                Bar x = b.get(k);
                double pc = b.get(k - 1).close();
                if (x.high() > x.low() && x.low() < pc && (x.close() - x.low()) / (x.high() - x.low()) >= 0.75) cnt++;
            }
            iRec.grade(cnt >= 3 ? 1.0 : cnt == 2 ? 0.5 : 0.0, cnt + "회/20일");
            Double asym = asymmetry(b, 20);
            if (asym == null) iAsym.unevaluated("상승일 또는 하락일 부재(분모 0 포함)");
            else ratioGrade(iAsym, asym, "중앙값 배율 ");
        } else { iRec.unevaluated("일봉 21개 미만"); iAsym.unevaluated("일봉 21개 미만"); }

        // ④ 상대강도 15
        relativeStrength(c, s, b, ctx);

        // ⑤ 변동성 축소 10 — 대금 증가와 저점 방어가 함께 있을 때만 긍정 배점
        ScoreItem iAtr = c.add(new ScoreItem(A_VOLA, "atr", "ATR 감소", 5, "ATR14/종가 최근5일 평균/직전20일 평균 ≤0.8 충족, <1 부분 (대금 증가·저점 방어 동반 시)"));
        ScoreItem iBb = c.add(new ScoreItem(A_VOLA, "bb", "볼린저밴드 폭 감소", 5, "BB폭(20,2σ)/중심선 최근5일/직전20일 ≤0.8 충족, <1 부분 (대금 증가·저점 방어 동반 시)"));
        Double atrR = Indicators.seriesRecentVsPrior(Indicators.atrPct(b, 14), 5, 20);
        Double bbR = Indicators.seriesRecentVsPrior(Indicators.bbWidth(b, 20), 5, 20);
        boolean companion = valR != null && valR > 1.0 && iLow.grade != null && iLow.grade >= 0.5;
        boolean companionKnown = valR != null && iLow.grade != null;
        volaGrade(iAtr, atrR, companion, companionKnown);
        volaGrade(iBb, bbR, companion, companionKnown);

        // ⑥ 재무·경쟁력 10
        Fundamentals f = ctx.fundamentals.get(s.symbol);
        String fsrc = f != null && f.source != null ? f.source : ctx.provider.fundamentalsSourceLabel();
        String fasof = f != null ? f.periodEnd : null;
        ScoreItem iH = c.add(new ScoreItem(A_FIN, "health", "재무 건전성", 4, "영업현금흐름·부채비율(≤100% 양호, >200% 악화)·유동비율(≥1 양호, <0.8 악화): 복수 양호 충족, 혼재 부분, 악화 0"));
        ScoreItem iG = c.add(new ScoreItem(A_FIN, "growth", "수익성 및 성장성", 3, "매출·영업이익 전년동기 대비: 둘 다 개선 충족, 혼재 부분, 악화 0"));
        ScoreItem iC = c.add(new ScoreItem(A_FIN, "compete", "사업 경쟁력", 3, "산업 내 경쟁력·지속성: 공시 등 확인된 근거로만 평가"));
        Object[] h = RiskRules.health(f);
        if (h[0] == null) iH.unevaluated((String) h[1]); else iH.grade((Double) h[0], (String) h[1]);
        Object[] g = RiskRules.growth(f);
        if (g[0] == null) iG.unevaluated((String) g[1]); else iG.grade((Double) g[0], (String) g[1]);
        iC.unevaluated("정성 항목 — 자동 판정 근거 미구현(미평가)");
        for (ScoreItem it : new ScoreItem[]{iH, iG, iC}) it.src(fsrc, fasof);

        for (ScoreItem it : c.items) {
            if (it.source == null) it.src(priceSrc, lastDate);
            if (it.area.equals(A_VOL) || it.area.equals(A_PRICE) || it.area.equals(A_VOLA) || it.area.equals(A_RS)) it.delayed = delayed;
        }

        // 근거 정리
        for (ScoreItem it : c.items) {
            if (!it.evaluated()) c.missing.add(it.area + " › " + it.name + ": " + (it.note == null ? "미평가" : it.note));
            else if (it.grade >= 1.0) c.pros.add(it.name + " — " + it.observed);
            else if (it.grade == 0.0 && (it.area.equals(A_FLOW) || it.area.equals(A_RS))) c.cons.add(it.name + " 미충족 — " + it.observed);
        }
        Double r20 = Indicators.ret(b, 20);
        if (r20 != null && r20 < -0.10 && volR != null && volR > 1.2) c.cons.add("주가 하락(" + Num.pct(r20) + ")과 거래량 증가 동반 — 물량 출회 가능성 우선 검토");
        if (volR != null && volR > 2.5) c.cons.add("단기 거래량 급증(" + Num.f2(volR) + "배) — 테마·실적발표·지수 리밸런싱·블록딜 여부 확인 필요");
        c.nextChecks.add("실적 발표 일정·지수 편입/리밸런싱·블록딜·희석 공시 여부 확인");
        if ("US".equals(s.market)) c.nextChecks.add("13F(분기·지연)·FINRA ATS(주간·지연)는 간접 자료 — 기관 순매수로 해석 금지");
        return c;
    }

    private static void info(ScoreCard c, Security s, List<Bar> b, MarketContext ctx) {
        c.info.put("board", s.board);
        c.info.put("price", b.isEmpty() ? null : b.get(b.size() - 1).close());
        c.info.put("lastBarDate", b.isEmpty() ? null : b.get(b.size() - 1).date().toString());
        c.info.put("ret20", Indicators.ret(b, 20));
        c.info.put("ret60", Indicators.ret(b, 60));
        c.info.put("marketCap", s.marketCap);
        c.info.put("mcapSource", s.mcapSource);
        c.info.put("avgValue20", Indicators.avgValue(b, 20));
        c.info.put("volRatio5v20", Indicators.recentVsPrior(b, 5, 20, 0));
        c.info.put("valueRatio5v20", Indicators.recentVsPrior(b, 5, 20, 1));
        c.info.put("sector", s.sector);
        c.info.put("benchmark", ctx.provider.benchmarkKey(s));
        c.info.put("priceSource", ctx.provider.priceSourceLabel());
    }

    /** 신규 후보 필터. 결과: PASS / FAIL / UNCONFIRMED. */
    static void filters(ScoreCard c, Security s, List<Bar> b, MarketContext ctx, boolean delayed) {
        String m = s.market;
        List<Map<String, Object>> fl = c.filters;
        String ex = s.typeExclusion();
        fl.add(filter("유형 제외(ETF·우선주·SPAC·관리/정지 등)", "해당 없음", ex == null ? (s.statusUnknown ? "상태정보 미제공(공시로 확인)" : "해당 없음") : ex,
                ex == null ? "PASS" : "FAIL", "상장목록"));
        Double px = b.isEmpty() ? null : b.get(b.size() - 1).close();
        fl.add(filter("페니주 제외", "종가 ≥ " + ("KR".equals(m) ? "1,000원" : "$5"), px == null ? "-" : Num.f2(px),
                px == null ? "UNCONFIRMED" : px >= Rules.pennyPrice(m) ? "PASS" : "FAIL", ctx.provider.priceSourceLabel()));
        fl.add(filter("데이터 최신성", "최근 봉 = 완료 거래일 " + ctx.asOf, b.isEmpty() ? "일봉 없음" : b.get(b.size() - 1).date().toString(),
                delayed ? "UNCONFIRMED" : "PASS", ctx.provider.priceSourceLabel()));
        double mt = Rules.masMcap(m);
        Map<String, Object> mc = boundary("시가총액", "≥ " + Num.money(m, mt), s.marketCap, mt, s.mcapApprox, s.mcapSource, true, m);
        if (s.mcapSource != null && s.mcapSource.startsWith("ADR(")) { mc.put("result", "UNCONFIRMED"); mc.put("observed", mc.get("observed") + " (ADR 주식수-ADS 비율 미반영 — 확정 불가)"); }
        fl.add(mc);
        double vt = Rules.masValue(m);
        Double av = Indicators.avgValue(b, 20);
        boolean vApprox = !b.isEmpty() && b.get(b.size() - 1).valueApprox();
        fl.add(boundary("최근 20거래일 평균 거래대금", "≥ " + Num.money(m, vt), av, vt, vApprox,
                ctx.provider.priceSourceLabel() + (vApprox ? " (종가×거래량 근사)" : ""), true, m));
        boolean sharesJump = Indicators.sharesChanged(b, 61);
        Double r20 = Indicators.ret(b, 20), r60 = Indicators.ret(b, 60);
        fl.add(retFilter("최근 20거래일 수익률", Rules.MAS_RET20_MAX, r20, sharesJump));
        fl.add(retFilter("최근 60거래일 수익률", Rules.MAS_RET60_MAX, r60, sharesJump));
        boolean anyFail = fl.stream().anyMatch(x -> "FAIL".equals(x.get("result")));
        boolean anyUnc = fl.stream().anyMatch(x -> "UNCONFIRMED".equals(x.get("result")));
        c.filterPassed = !anyFail && !anyUnc;
        c.filterUnconfirmed = !anyFail && anyUnc;
    }

    static Map<String, Object> filter(String name, String crit, String obs, String result, String src) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("name", name);
        x.put("criterion", crit);
        x.put("observed", obs);
        x.put("result", result);
        x.put("source", src);
        return x;
    }

    static Map<String, Object> boundary(String name, String crit, Double v, double thr, boolean approx, String src, boolean ge, String m) {
        if (v == null) return filter(name, crit, "미확보", "UNCONFIRMED", src);
        boolean pass = ge ? v >= thr : v <= thr;
        String res = pass ? "PASS" : "FAIL";
        if (approx && Math.abs(v / thr - 1) <= Rules.APPROX_BOUNDARY) res = "UNCONFIRMED";
        return filter(name, crit + (approx ? " (근사값: ±10% 경계 미확정)" : ""), Num.money(m, v), res, src);
    }

    static Map<String, Object> retFilter(String name, double max, Double r, boolean sharesJump) {
        if (r == null) return filter(name, "≤ +" + Math.round(max * 100) + "%", "자료 부족", "UNCONFIRMED", "일봉");
        if (sharesJump) return filter(name, "≤ +" + Math.round(max * 100) + "%", Num.pct(r) + " (상장주식수 변동 — 원주가 불연속 가능)", "UNCONFIRMED", "일봉");
        // 부동소수 경계 보호: 정확히 +15.00% 는 통과
        boolean pass = r <= max + 1e-9;
        return filter(name, "≤ +" + Math.round(max * 100) + "%", Num.pct(r), pass ? "PASS" : "FAIL", "일봉");
    }

    private static void flows(ScoreCard c, Security s, List<Bar> b, MarketContext ctx) {
        ScoreItem iF = c.add(new ScoreItem(A_FLOW, "foreign", "외국인 순매수 지속성", 8, "20일 누적 양수 + 5일 또는 60일 양수 충족, 20일만 양수 부분"));
        ScoreItem iI = c.add(new ScoreItem(A_FLOW, "inst", "기관 순매수 지속성", 8, "20일 누적 양수 + 5일 또는 60일 양수 충족, 20일만 양수 부분"));
        ScoreItem iP = c.add(new ScoreItem(A_FLOW, "pension", "연기금 등 세부 수급", 5, "연기금 20일 누적 양수 + 5/60일 동반 (기관 합계와 합산 금지)"));
        ScoreItem iH = c.add(new ScoreItem(A_FLOW, "hold", "외국인 보유 비중 변화", 4, "20일 증가 + 60일 증가 충족, 20일만 증가 부분"));
        String fsrc = ctx.provider.flowSourceLabel();
        if (!ctx.provider.flowsSupported()) {
            String why = "미국은 종목별 투자자 순매수 직접 자료 없음 — 미평가(13F·ATS는 간접 자료로 별도 표시, 점수 미반영)";
            for (ScoreItem it : new ScoreItem[]{iF, iI, iP, iH}) it.unevaluated(why).src(fsrc, null);
            return;
        }
        List<FlowDay> fl = ctx.flows.get(s.symbol);
        if (fl == null || fl.size() < 20) {
            String why = fl == null ? "수급 자료 수집 실패/미확보" : "수급 자료 " + fl.size() + "일(20일 미만)";
            for (ScoreItem it : new ScoreItem[]{iF, iI, iP, iH}) it.unevaluated(why).src(fsrc, null);
            return;
        }
        String last = fl.get(fl.size() - 1).date().toString();
        boolean del = !last.equals(ctx.asOf.toString());
        double tv20 = 0;
        Map<java.time.LocalDate, Bar> byDate = new java.util.HashMap<>();
        for (Bar x : b) byDate.put(x.date(), x);
        for (int k = Math.max(0, fl.size() - 20); k < fl.size(); k++) {
            Bar x = byDate.get(fl.get(k).date());
            if (x != null) tv20 += x.tradeValue();
        }
        flowItem(iF, fl, 0, tv20, s.market);
        flowItem(iI, fl, 1, tv20, s.market);
        iP.unevaluated("원천이 연기금 세부 분류를 제공하지 않음(app-3)");
        // 보유 비중
        Double h0 = fl.get(fl.size() - 1).foreignHoldPct();
        Double h20 = fl.size() > 20 ? fl.get(fl.size() - 21).foreignHoldPct() : null;
        Double h60 = fl.size() > 60 ? fl.get(fl.size() - 61).foreignHoldPct() : null;
        if (h0 == null || h20 == null) iH.unevaluated("보유 비중 자료 부족");
        else {
            double d20 = h0 - h20;
            Double d60 = h60 == null ? null : h0 - h60;
            double gr = d20 > 0 ? (d60 != null && d60 > 0 ? 1.0 : 0.5) : 0.0;
            iH.grade(gr, String.format("20일 %+.2f%%p / 60일 %s", d20, d60 == null ? "자료부족" : String.format("%+.2f%%p", d60)));
        }
        for (ScoreItem it : new ScoreItem[]{iF, iI, iP, iH}) {
            it.src(fsrc, last);
            it.delayed = del;
            if (it.key.equals("foreign") || it.key.equals("inst")) { it.substitute = true; it.note = (it.note == null ? "" : it.note + "; ") + "금액=수량×종가 근사"; }
        }
    }

    private static void flowItem(ScoreItem it, List<FlowDay> fl, int who, double tv20, String market) {
        Double s5 = sumFlow(fl, 5, who), s20 = sumFlow(fl, 20, who), s60 = fl.size() >= 60 ? sumFlow(fl, 60, who) : null;
        if (s20 == null) { it.unevaluated("20일 수급 자료 부족"); return; }
        double g = s20 > 0 ? ((s5 != null && s5 > 0) || (s60 != null && s60 > 0) ? 1.0 : 0.5) : 0.0;
        String ratio = tv20 > 0 ? String.format(" (20일 거래대금 대비 %+.2f%%)", s20 / tv20 * 100) : "";
        it.grade(g, "5일 " + Num.money(market, s5) + " / 20일 " + Num.money(market, s20) + " / 60일 " + (s60 == null ? "자료부족" : Num.money(market, s60)) + ratio);
    }

    static Double sumFlow(List<FlowDay> fl, int n, int who) {
        if (fl.size() < n) return null;
        double s = 0;
        int valid = 0;
        for (int k = fl.size() - n; k < fl.size(); k++) {
            FlowDay d = fl.get(k);
            Double q = who == 0 ? d.foreignQty() : d.instQty();
            if (q == null) continue;
            s += q * d.close();
            valid++;
        }
        return valid < n * 0.9 ? null : s;
    }

    private static void relativeStrength(ScoreCard c, Security s, List<Bar> b, MarketContext ctx) {
        ScoreItem iM = c.add(new ScoreItem(A_RS, "market", "시장 대비 초과수익률", 7, "20일·60일 초과수익률 모두 양수 충족, 하나 부분"));
        ScoreItem iS = c.add(new ScoreItem(A_RS, "sector", "업종 대비 초과수익률", 5, "업종 동일 종목 중앙값 대비 20일·60일 모두 양수 충족, 하나 부분 (app-5)"));
        ScoreItem iD = c.add(new ScoreItem(A_RS, "defense", "시장 하락 시 방어력", 3, "최근20일 시장 하락일(≥5일) 중 시장보다 강한 날 ≥60% 충족, >50% 부분"));
        List<Bar> bm = ctx.bench(s);
        String bmKey = ctx.provider.benchmarkKey(s);
        Double r20 = Indicators.ret(b, 20), r60 = Indicators.ret(b, 60);
        Double m20 = Indicators.ret(bm, 20), m60 = Indicators.ret(bm, 60);
        if (r20 == null || r60 == null || m20 == null || m60 == null) iM.unevaluated("종목 또는 벤치마크(" + bmKey + ") 수익률 자료 부족");
        else {
            double e20 = r20 - m20, e60 = r60 - m60;
            int pos = (e20 > 0 ? 1 : 0) + (e60 > 0 ? 1 : 0);
            iM.grade(pos == 2 ? 1.0 : pos == 1 ? 0.5 : 0.0, bmKey + " 대비 20일 " + Num.pct(e20) + " / 60일 " + Num.pct(e60));
            c.info.put("excess20", e20);
            c.info.put("excess60", e60);
        }
        iM.src(ctx.provider.priceSourceLabel() + " / 벤치마크 " + bmKey, bm.isEmpty() ? null : bm.get(bm.size() - 1).date().toString());
        List<MarketContext.PeerStat> peers = ctx.peersOf(s);
        List<Double> p20 = new ArrayList<>(), p60 = new ArrayList<>();
        for (MarketContext.PeerStat p : peers) { if (p.r20() != null) p20.add(p.r20()); if (p.r60() != null) p60.add(p.r60()); }
        if (s.sector == null) iS.unevaluated("업종 정보 미확보");
        else if (p20.size() < 4 || p60.size() < 4 || r20 == null || r60 == null) iS.unevaluated("업종 비교 종목 " + p20.size() + "개(<4) 또는 수익률 부족");
        else {
            double e20 = r20 - Num.median(p20), e60 = r60 - Num.median(p60);
            int pos = (e20 > 0 ? 1 : 0) + (e60 > 0 ? 1 : 0);
            iS.grade(pos == 2 ? 1.0 : pos == 1 ? 0.5 : 0.0, "업종 " + s.sector + "(" + p20.size() + "종목) 중앙값 대비 20일 " + Num.pct(e20) + " / 60일 " + Num.pct(e60));
            iS.substitute = true;
        }
        Map<java.time.LocalDate, Double> bmRet = dailyRet(bm);
        Map<java.time.LocalDate, Double> stRet = dailyRet(b);
        int downDays = 0, stronger = 0;
        for (int k = Math.max(1, b.size() - 20); k < b.size(); k++) {
            java.time.LocalDate d = b.get(k).date();
            Double mr = bmRet.get(d), sr = stRet.get(d);
            if (mr == null || sr == null || mr >= 0) continue;
            downDays++;
            if (sr > mr) stronger++;
        }
        if (downDays < 5) iD.unevaluated("최근20일 시장 하락일 " + downDays + "일(<5)");
        else {
            double fr = (double) stronger / downDays;
            iD.grade(fr >= 0.6 ? 1.0 : fr > 0.5 ? 0.5 : 0.0, stronger + "/" + downDays + "일 시장보다 강함");
        }
    }

    static Map<java.time.LocalDate, Double> dailyRet(List<Bar> b) {
        Map<java.time.LocalDate, Double> m = new java.util.HashMap<>();
        for (int k = 1; k < b.size(); k++) {
            double pc = b.get(k - 1).close();
            if (pc > 0) m.put(b.get(k).date(), b.get(k).close() / pc - 1);
        }
        return m;
    }

    private static void ratioGrade(ScoreItem it, Double r, String prefix) {
        if (r == null) { it.unevaluated("자료 부족(필요 세션 미달 또는 분모 0)"); return; }
        it.grade(r >= 1.2 ? 1.0 : r > 1.0 ? 0.5 : 0.0, prefix + Num.f2(r));
    }

    private static void volaGrade(ScoreItem it, Double r, boolean companion, boolean companionKnown) {
        if (r == null) { it.unevaluated("자료 부족"); return; }
        double g = r <= 0.8 ? 1.0 : r < 1.0 ? 0.5 : 0.0;
        if (g > 0 && !companionKnown) { it.unevaluated("변동성 축소(" + Num.f2(r) + ")는 있으나 동반 조건(대금 증가·저점 방어) 미확인"); return; }
        if (g > 0 && !companion) {
            it.grade(0.0, "배율 " + Num.f2(r));
            it.note = "변동성 축소만으로 매집 판단 안 함 — 대금 증가·저점 방어 동반 없음";
            return;
        }
        it.grade(g, "배율 " + Num.f2(r));
    }

    static Double upDownVolumeRatio(List<Bar> b, int n) {
        if (b.size() < n + 1) return null;
        double up = 0, dn = 0;
        int nu = 0, nd = 0;
        for (int k = b.size() - n; k < b.size(); k++) {
            double pc = b.get(k - 1).close();
            if (b.get(k).close() > pc) { up += b.get(k).volume(); nu++; }
            else if (b.get(k).close() < pc) { dn += b.get(k).volume(); nd++; }
        }
        if (nu == 0 || nd == 0 || dn == 0) return null;
        return (up / nu) / (dn / nd);
    }

    static Double asymmetry(List<Bar> b, int n) {
        List<Double> up = new ArrayList<>(), dn = new ArrayList<>();
        for (int k = b.size() - n; k < b.size(); k++) {
            double r = b.get(k).close() / b.get(k - 1).close() - 1;
            if (r > 0) up.add(r); else if (r < 0) dn.add(-r);
        }
        if (up.isEmpty() || dn.isEmpty()) return null;
        double md = Num.median(dn);
        if (md == 0) return null;
        return Num.median(up) / md;
    }
}
