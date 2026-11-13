package screener.engine;

import screener.model.Bar;
import screener.model.DisclosureCheck;
import screener.model.FlowDay;
import screener.model.Fundamentals;
import screener.model.Security;
import screener.util.Num;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 종목 투자기회 점수(OIS). 원본 02의 5개 영역 배점, skill contrarian.md 초기 판정.
 * MAS·MSI와 독립 계산하며 합산하지 않는다. MSI는 시나리오(분할 진입 단계) 설명에만 참조한다.
 */
public final class OisScorer {
    private OisScorer() { }

    public static final String A_FIN = "재무·경쟁력";
    public static final String A_GROW = "성장";
    public static final String A_DD = "낙폭·가격";
    public static final String A_FLOW = "수급·거래량";
    public static final String A_REV = "기술적 반전";

    public static ScoreCard score(Security s, MarketContext ctx) {
        ScoreCard c = new ScoreCard("OIS", Rules.OIS_VERSION, s.market, s.symbol);
        c.name = s.name;
        c.asOf = ctx.asOf.toString();
        List<Bar> b = ctx.bars.getOrDefault(s.symbol, List.of());
        String priceSrc = ctx.provider.priceSourceLabel();
        String lastDate = b.isEmpty() ? null : b.get(b.size() - 1).date().toString();
        boolean delayed = lastDate == null || !lastDate.equals(ctx.asOf.toString());
        Fundamentals f = ctx.fundamentals.get(s.symbol);
        String fsrc = f != null && f.source != null ? f.source : ctx.provider.fundamentalsSourceLabel();
        String fasof = f != null ? f.periodEnd : null;

        // 재무·경쟁력 25
        ScoreItem iH = c.add(new ScoreItem(A_FIN, "health", "현금흐름·부채·유동성", 15, "복수 양호 충족, 혼재 부분, 악화 0"));
        ScoreItem iM = c.add(new ScoreItem(A_FIN, "moat", "경쟁우위", 10, "공시 등 확인된 근거로만 평가"));
        Object[] h = RiskRules.health(f);
        if (h[0] == null) iH.unevaluated((String) h[1]); else iH.grade((Double) h[0], (String) h[1]);
        iM.unevaluated("정성 항목 — 자동 판정 근거 미구현(미평가)");
        // 성장 20
        ScoreItem iG = c.add(new ScoreItem(A_GROW, "trend", "최신 매출·이익 추세", 12, "매출·영업이익 전년동기 대비 둘 다 개선 충족, 혼재 부분, 악화 0"));
        ScoreItem iGd = c.add(new ScoreItem(A_GROW, "guide", "가이던스·전망", 8, "회사 가이던스/컨센서스 변화"));
        Object[] g = RiskRules.growth(f);
        if (g[0] == null) iG.unevaluated((String) g[1]); else iG.grade((Double) g[0], (String) g[1]);
        iGd.unevaluated("무료 공급원에서 가이던스·전망 정량 자료 미확보(미평가)");
        for (ScoreItem it : new ScoreItem[]{iH, iM, iG, iGd}) it.src(fsrc, fasof);

        // 낙폭·가격 20
        ScoreItem iDd = c.add(new ScoreItem(A_DD, "drawdown", "최근 60일 고점 대비 낙폭", 8, "≤ -15% 충족, ≤ -8% 부분"));
        ScoreItem iVal = c.add(new ScoreItem(A_DD, "valuation", "밸류에이션(동종 비교)", 12, "PER ≤ 업종 중앙값×0.8 충족, ≤ 중앙값 부분 (이익 급감 시 착시 확인 → 보류)"));
        Double dd = Indicators.drawdown(b, 60);
        if (dd == null) iDd.unevaluated("일봉 60개 미만");
        else iDd.grade(dd <= -0.15 ? 1.0 : dd <= -0.08 ? 0.5 : 0.0, "낙폭 " + Num.pct(dd));
        iDd.src(priceSrc, lastDate);
        valuation(iVal, s, f, ctx);
        iVal.src(fsrc + " + " + priceSrc, fasof);

        // 수급·거래량 15
        ScoreItem iFl = c.add(new ScoreItem(A_FLOW, "flow", "확인 가능한 수급 흐름 개선", 8, "외국인+기관 5일 순매수 양수 & 직전 5일보다 개선 충족, 둘 중 하나 부분"));
        ScoreItem iRv = c.add(new ScoreItem(A_FLOW, "recvol", "회복일 거래량", 7, "최근10일 중 상승 마감일 거래량 ≥ 직전20일 평균×1.5 충족, ×1.2 부분"));
        flowImprove(iFl, s, ctx);
        if (b.size() >= 30) {
            double best = 0;
            for (int k = b.size() - 10; k < b.size(); k++) {
                if (b.get(k).close() <= b.get(k - 1).close()) continue;
                Double mm = Indicators.volMultipleAt(b, k, 20);
                if (mm != null) best = Math.max(best, mm);
            }
            iRv.grade(best >= 1.5 ? 1.0 : best >= 1.2 ? 0.5 : 0.0, "최대 배율 " + Num.f2(best));
        } else iRv.unevaluated("일봉 30개 미만");
        iRv.src(priceSrc, lastDate);

        // 기술적 반전 20
        ScoreItem iLow = c.add(new ScoreItem(A_REV, "low5", "최근 5일 저점 ≥ 직전 5일 저점", 7, "충족 시 1"));
        ScoreItem iMa = c.add(new ScoreItem(A_REV, "ma20", "종가 20일선 회복·유지", 7, "2일 이상 유지 충족, 회복 첫날 부분"));
        ScoreItem iRel = c.add(new ScoreItem(A_REV, "rel5", "5일 시장·업종 상대수익률", 6, "둘 다 양수 충족, 하나 부분"));
        if (b.size() >= 21) {
            double l1 = Double.MAX_VALUE, l0 = Double.MAX_VALUE;
            for (int k = b.size() - 5; k < b.size(); k++) l1 = Math.min(l1, b.get(k).low());
            for (int k = b.size() - 10; k < b.size() - 5; k++) l0 = Math.min(l0, b.get(k).low());
            iLow.grade(l1 >= l0 ? 1.0 : 0.0, String.format("저점 비 %.3f", l1 / l0));
            Double ma = Indicators.sma(b, 20);
            Double maPrev = Indicators.sma(b.subList(0, b.size() - 1), 20);
            double cl = b.get(b.size() - 1).close(), pcl = b.get(b.size() - 2).close();
            boolean above = ma != null && cl >= ma, abovePrev = maPrev != null && pcl >= maPrev;
            iMa.grade(above ? (abovePrev ? 1.0 : 0.5) : 0.0, above ? (abovePrev ? "20일선 위 유지" : "20일선 회복 첫날") : "20일선 아래");
        } else { iLow.unevaluated("일봉 부족"); iMa.unevaluated("일봉 부족"); }
        Double r5 = Indicators.ret(b, 5), m5 = Indicators.ret(ctx.bench(s), 5);
        List<Double> p5 = new ArrayList<>();
        for (MarketContext.PeerStat p : ctx.peersOf(s)) if (p.r5() != null) p5.add(p.r5());
        if (r5 == null || m5 == null) iRel.unevaluated("5일 수익률 자료 부족");
        else {
            boolean mPos = r5 - m5 > 0;
            if (p5.size() >= 4) {
                boolean sPos = r5 - Num.median(p5) > 0;
                iRel.grade(mPos && sPos ? 1.0 : (mPos || sPos) ? 0.5 : 0.0,
                        "시장 대비 " + Num.pct(r5 - m5) + ", 업종 대비 " + Num.pct(r5 - Num.median(p5)));
            } else {
                iRel.grade(mPos ? 0.5 : 0.0, "시장 대비 " + Num.pct(r5 - m5) + " (업종 비교 불가)");
                iRel.note = "업종 비교 종목 부족 — 시장만으로 최대 부분 충족";
            }
        }
        for (ScoreItem it : new ScoreItem[]{iLow, iMa, iRel}) it.src(priceSrc, lastDate);
        for (ScoreItem it : c.items) if (it.area.equals(A_DD) || it.area.equals(A_REV) || it.key.equals("recvol")) it.delayed = delayed;

        // 정보·분류
        Double r20 = Indicators.ret(b, 20), r60 = Indicators.ret(b, 60);
        c.info.put("board", s.board);
        c.info.put("price", b.isEmpty() ? null : b.get(b.size() - 1).close());
        c.info.put("lastBarDate", lastDate);
        c.info.put("marketCap", s.marketCap);
        c.info.put("ret20", r20);
        c.info.put("ret60", r60);
        c.info.put("drawdown60", dd);
        c.info.put("atrPct", last(Indicators.atrPct(b, 14)));
        c.info.put("atrChange5v20", Indicators.seriesRecentVsPrior(Indicators.atrPct(b, 14), 5, 20));
        c.info.put("realizedVol20", Indicators.realizedVol(b, 20));
        c.info.put("realizedVol60", Indicators.realizedVol(b, 60));
        c.info.put("volRatio5v20", Indicators.recentVsPrior(b, 5, 20, 0));
        c.info.put("volMultiple", b.size() > 20 ? Indicators.volMultipleAt(b, b.size() - 1, 20) : null);
        c.info.put("rsi14", Indicators.rsi(b, 14));
        c.info.put("benchmark", ctx.provider.benchmarkKey(s));
        c.info.put("sector", s.sector);

        for (ScoreItem it : c.items) {
            if (!it.evaluated()) c.missing.add(it.area + " › " + it.name + ": " + (it.note == null ? "미평가" : it.note));
            else if (it.grade >= 1.0) c.pros.add(it.name + " — " + it.observed);
            else if (it.grade == 0.0 && (it.area.equals(A_FIN) || it.area.equals(A_GROW))) c.cons.add(it.name + " 미충족 — " + it.observed);
        }
        c.nextChecks.add("하락 원인(시장 동반 vs 기업 고유) 공시·뉴스 확인");
        c.nextChecks.add("손절 또는 투자 가설 무효화 기준을 진입 전에 설정");
        return c;
    }

    private static Double last(List<Double> l) { return l.isEmpty() ? null : l.get(l.size() - 1); }

    private static void valuation(ScoreItem it, Security s, Fundamentals f, MarketContext ctx) {
        if (f == null || f.annualNetIncome == null || s.marketCap == null) { it.unevaluated("연간 순이익 또는 시총 미확보"); return; }
        if (f.annualNetIncome <= 0) { it.grade(0.0, "순이익 적자 — 할인 근거 아님"); return; }
        if (f.netIncome != null && f.netIncomePrev != null && f.netIncomePrev > 0 && f.netIncome / f.netIncomePrev - 1 < -0.3) {
            it.unevaluated("최근 분기 순이익 전년동기 대비 30% 이상 감소 — 이익 붕괴에 따른 PER 착시 가능, 판정 보류");
            return;
        }
        double pe = s.marketCap / f.annualNetIncome;
        List<Double> pes = new ArrayList<>();
        for (MarketContext.PeerStat p : ctx.peersOf(s)) if (p.pe() != null && p.pe() > 0 && p.pe() < 500) pes.add(p.pe());
        if (pes.size() < 4) { it.unevaluated("PER " + Num.f2(pe) + " — 업종 비교 종목 " + pes.size() + "개(<4)"); return; }
        double med = Num.median(pes);
        it.grade(pe <= med * 0.8 ? 1.0 : pe <= med ? 0.5 : 0.0, "PER " + Num.f2(pe) + " vs 업종 중앙값 " + Num.f2(med) + " (" + pes.size() + "종목)"
                + (f.earningsNote != null ? ", " + f.earningsNote : ""));
        it.substitute = true;
    }

    private static void flowImprove(ScoreItem it, Security s, MarketContext ctx) {
        it.src(ctx.provider.flowSourceLabel(), null);
        if (!ctx.provider.flowsSupported()) { it.unevaluated("미국: 종목별 직접 기관 수급 자료 없음 — 미평가(간접 자료는 별도 표시)"); return; }
        List<FlowDay> fl = ctx.flows.get(s.symbol);
        if (fl == null || fl.size() < 10) { it.unevaluated("수급 자료 미확보 또는 10일 미만"); return; }
        double cur = 0, prev = 0;
        for (int k = fl.size() - 5; k < fl.size(); k++) cur += net(fl.get(k));
        for (int k = fl.size() - 10; k < fl.size() - 5; k++) prev += net(fl.get(k));
        boolean pos = cur > 0, imp = cur > prev;
        it.grade(pos && imp ? 1.0 : (pos || imp) ? 0.5 : 0.0, "외국인+기관 5일 " + Num.money(s.market, cur) + " (직전5일 " + Num.money(s.market, prev) + ")");
        it.substitute = true;
        it.asOf = fl.get(fl.size() - 1).date().toString();
        it.delayed = !it.asOf.equals(ctx.asOf.toString());
    }

    private static double net(FlowDay d) {
        double f = d.foreignQty() == null ? 0 : d.foreignQty(), i = d.instQty() == null ? 0 : d.instQty();
        return (f + i) * d.close();
    }

    /** 하락 원인 구분: 시장 공포에 따른 동반 하락 vs 기업 고유 악재. */
    public static String classifyDecline(ScoreCard c, Security s, MarketContext ctx, DisclosureCheck dc) {
        List<Bar> b = ctx.bars.getOrDefault(s.symbol, List.of());
        Double r20 = Indicators.ret(b, 20), m20 = Indicators.ret(ctx.bench(s), 20);
        List<Double> p20 = new ArrayList<>();
        for (MarketContext.PeerStat p : ctx.peersOf(s)) if (p.r20() != null) p20.add(p.r20());
        boolean riskEvent = dc != null && dc.ok && (dc.hasBlock() || dc.hasReview());
        ScoreItem growth = c.items.stream().filter(i -> i.key.equals("trend")).findFirst().orElse(null);
        boolean earningsBad = growth != null && growth.grade != null && growth.grade == 0.0;
        if (r20 == null || m20 == null) return "판단 불가(수익률 자료 부족)";
        double ex = r20 - m20;
        String base = String.format("20일 %s, 시장 %s, 초과 %s", Num.pct(r20), Num.pct(m20), Num.pct(ex));
        if (riskEvent || earningsBad) return "기업 고유 악재 의심 — " + (riskEvent ? "위험 공시 존재" : "실적 악화") + " (" + base + ")";
        if (r20 >= 0) return "하락 아님 (" + base + ")";
        if (ex >= -0.05) return (m20 < 0 ? "시장 동반 하락(시장 요인 중심)" : "시장 대비 소폭 약세") + " (" + base + ")";
        if (p20.size() >= 4 && r20 - Num.median(p20) >= -0.05) return "업종 동반 하락 (" + base + ", 업종 중앙값 " + Num.pct(Num.median(p20)) + ")";
        return "종목 고유 하락(원인 미확인 — 공시·뉴스 확인 필요) (" + base + ")";
    }

    /** 원본 02 공포 기반 4단계 분할 진입 시나리오(20/30/30/20) — 보고용, 주문 아님. */
    public static List<Map<String, Object>> scenario(Security s, MarketContext ctx, ScoreCard c) {
        List<Bar> b = ctx.bars.getOrDefault(s.symbol, List.of());
        List<Bar> bm = ctx.bench(s);
        Double dd = Indicators.drawdown(b, 60);
        List<Map<String, Object>> out = new ArrayList<>();
        Boolean s1 = ctx.msiScore == null || dd == null ? null : (Math.round(ctx.msiScore) <= 15 && dd <= -0.15);
        out.add(step(1, 20, "극단적 공포 및 우량주 급락 확인", s1,
                "MSI " + (ctx.msiScore == null ? "미산출" : Math.round(ctx.msiScore)) + ", 60일 고점 대비 " + Num.pct(dd)));
        Boolean s2 = null;
        if (b.size() >= 26) {
            s2 = false;
            for (int k = b.size() - 5; k < b.size(); k++) {
                Double m = Indicators.volMultipleAt(b, k, 20);
                if (b.get(k).close() < b.get(k - 1).close() && m != null && m >= 1.5) s2 = true;
            }
        }
        out.add(step(2, 30, "투매 및 거래량 급증 확인", s2, "최근5일 하락일 중 거래량 ≥1.5배 여부"));
        Boolean s3 = null;
        if (ctx.msiCh5 != null && bm.size() >= 11) {
            Double r5 = Indicators.ret(bm, 5), r5p = Indicators.ret(bm.subList(0, bm.size() - 5), 5);
            s3 = ctx.msiCh5 > 0 && r5 != null && r5p != null && r5 > r5p;
        }
        out.add(step(3, 30, "시장 하락 둔화 및 심리 반전", s3, "MSI 5일 변화 " + (ctx.msiCh5 == null ? "미산출" : String.format("%+.1f", ctx.msiCh5)) + ", 지수 5일 수익률 개선 여부"));
        Boolean s4 = null;
        Double ma = Indicators.sma(b, 20), bma = Indicators.sma(bm, 20);
        if (ma != null && bma != null) s4 = b.get(b.size() - 1).close() >= ma && bm.get(bm.size() - 1).close() >= bma;
        out.add(step(4, 20, "시장 및 개별 종목의 추세 회복", s4, "종목·지수 종가 ≥ 20일선"));
        return out;
    }

    private static Map<String, Object> step(int n, int pct, String cond, Boolean met, String obs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", n);
        m.put("weightPct", pct);
        m.put("condition", cond);
        m.put("met", met);
        m.put("observed", obs);
        return m;
    }
}
