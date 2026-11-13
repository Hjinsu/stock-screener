package screener.provider;

import screener.model.Bar;
import screener.model.DisclosureCheck;
import screener.model.FlowDay;
import screener.model.Fundamentals;
import screener.model.Security;
import screener.model.SourceStatus;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 데모(합성) 데이터 공급자 — 실제 시세가 아님. `demo` 명령과 오프라인 테스트에서만 사용하며
 * 결과는 별도 DB(demo.db)에 저장된다. 모든 이름은 '데모'/'DM' 접두사를 가진다.
 */
public final class DemoProvider implements MarketDataProvider {
    public static final int DAYS = 420;
    public static final LocalDate END = LocalDate.of(2026, 11, 12);

    private final String market;
    private final SourceStatus.Registry reg = new SourceStatus.Registry();
    private final List<LocalDate> cal = new ArrayList<>();
    private final Map<String, Spec> specs = new LinkedHashMap<>();
    private final Map<String, List<Bar>> series = new HashMap<>();
    private final Map<String, List<Bar>> index = new HashMap<>();
    private final Map<String, List<FlowDay>> flows = new HashMap<>();
    private LocalDate asOf;

    record Seg(int len, double drift, double vol, double volMult, double upBias, boolean recover) { }

    static final class Spec {
        String sym, name, board, sector, kind;
        double shares, p0, baseVol;
        boolean etf, pref, spac, nonCommon, noFund;
        List<Seg> segs = new ArrayList<>();
        List<String[]> events = new ArrayList<>(); // {dayIndex, title}
        double peTarget = 16, revG = 0.10, opG = 0.15;
    }

    public DemoProvider(String market) {
        this.market = market;
        LocalDate d = END;
        while (cal.size() < DAYS) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) cal.add(0, d);
            d = d.minusDays(1);
        }
        build();
        this.asOf = END;
    }

    public List<LocalDate> calendar() { return cal; }

    public void setAsOf(LocalDate d) { this.asOf = d; }

    private Spec spec(String sym, String name, String board, String sector, String kind, double shares, double p0, double baseVol) {
        Spec s = new Spec();
        s.sym = sym; s.name = name; s.board = board; s.sector = sector; s.kind = kind; s.shares = shares; s.p0 = p0; s.baseVol = baseVol;
        specs.put(sym, s);
        return s;
    }

    private void build() {
        int n = DAYS;
        boolean kr = "KR".equals(market);
        String[] sectors = kr ? new String[]{"D-반도체", "D-화학", "D-자동차", "D-바이오"} : new String[]{"SIC36", "SIC28", "SIC37", "SIC73"};
        // 크기: KR 시총 원, US 달러
        double big = kr ? 12e12 : 60e9, small = kr ? 1e12 : 3e9;
        double px = kr ? 50000 : 120;
        java.util.function.BiFunction<Double, Double, Double> sharesFor = (mcap, p) -> mcap / p;

        // 축적(매집 의심) 패턴: 횡보+상승일 거래량 증가 → 이후 상승
        Spec acc = spec(kr ? "900010" : "DMACC", kr ? "데모반도체" : "Demo Accum Inc", kr ? "KOSPI" : "NASDAQ", sectors[0], "ACC", sharesFor.apply(big, px), px, kr ? 400000 : 1500000);
        acc.segs.add(new Seg(n - 110, 0.0004, 0.015, 1.0, 1.0, false));
        acc.segs.add(new Seg(45, -0.0003, 0.008, 1.1, 1.6, true));
        acc.segs.add(new Seg(15, 0.0, 0.006, 1.7, 1.8, true));
        acc.segs.add(new Seg(50, 0.004, 0.012, 1.4, 1.3, false));
        Spec acc2 = spec(kr ? "900020" : "DMACC2", kr ? "데모화학" : "Demo Chem Corp", kr ? "KOSPI" : "NYSE", sectors[1], "ACC", sharesFor.apply(big * 0.7, px), px, kr ? 300000 : 1200000);
        acc2.segs.add(new Seg(n - 110, 0.0003, 0.014, 1.0, 1.0, false));
        acc2.segs.add(new Seg(60, -0.0002, 0.008, 1.2, 1.7, true));
        acc2.segs.add(new Seg(50, -0.003, 0.02, 1.6, 0.7, false));
        acc2.events.add(new String[]{String.valueOf(n - 30), kr ? "횡령ㆍ배임혐의발생" : "8-K|4.02"});
        Spec surge = spec(kr ? "900030" : "DMSURG", kr ? "데모자동차" : "Demo Surge Motors", kr ? "KOSPI" : "NYSE", sectors[2], "SURGE", sharesFor.apply(big, px), px, kr ? 350000 : 1500000);
        surge.segs.add(new Seg(n - 25, 0.0003, 0.013, 1.0, 1.0, false));
        surge.segs.add(new Seg(25, 0.012, 0.012, 1.8, 1.2, false));
        spec(kr ? "900050" : "DMSMAL", kr ? "데모소형" : "Demo Small Co", kr ? "KOSDAQ" : "NASDAQ", sectors[3], "RAND", sharesFor.apply(small, px), px, 100000).segs.add(new Seg(n, 0.0002, 0.02, 1, 1, false));
        spec(kr ? "900060" : "DMILLQ", kr ? "데모비유동" : "Demo Illiquid Hldg", kr ? "KOSPI" : "NYSE", sectors[1], "RAND", sharesFor.apply(big * 0.6, px), px, kr ? 3000 : 20000).segs.add(new Seg(n, 0.0002, 0.012, 1, 1, false));
        Spec nof = spec(kr ? "900070" : "DMNOF", kr ? "데모무재무" : "Demo NoFin Ltd", kr ? "KOSPI" : "NASDAQ", sectors[0], "ACC", sharesFor.apply(big * 0.9, px), px, kr ? 400000 : 1500000);
        nof.noFund = true;
        nof.segs.addAll(acc.segs);
        Spec risk = spec(kr ? "900080" : "DMRISK", kr ? "데모증자" : "Demo Offering Inc", kr ? "KOSDAQ" : "NASDAQ", sectors[3], "ACC", sharesFor.apply(big * 0.8, px), px, kr ? 400000 : 1500000);
        risk.segs.addAll(acc.segs);
        risk.events.add(new String[]{String.valueOf(n - 80), kr ? "주요사항보고서(유상증자결정)" : "S-1|"});
        Spec pref = spec(kr ? "900015" : "DMWT", kr ? "데모반도체우" : "Demo Accum Warrant", kr ? "KOSPI" : "NASDAQ", sectors[0], "RAND", sharesFor.apply(big * 0.5, px), px, 200000);
        pref.segs.add(new Seg(n, 0.0002, 0.012, 1, 1, false));
        if (kr) pref.pref = true; else pref.nonCommon = true;
        Spec etf = spec(kr ? "900099" : "DMETF", kr ? "데모 ETF" : "Demo 2x Leveraged ETF", kr ? "KOSPI" : "NYSE", null, "RAND", sharesFor.apply(big, px), px, 800000);
        etf.etf = true;
        etf.segs.add(new Seg(n, 0.0003, 0.015, 1, 1, false));
        if (kr) {
            Spec sp = spec("900100", "데모스팩1호", "KOSDAQ", null, "RAND", 1e7, 2000, 50000);
            sp.spac = true;
            sp.segs.add(new Seg(n, 0, 0.002, 1, 1, false));
        }
        // 역발상: 시장 공포 구간 급락 후 반등하는 우량주
        for (int k = 1; k <= 2; k++) {
            Spec dip = spec(kr ? "90011" + k : "DMDIP" + k, kr ? "데모우량" + k : "Demo Quality " + k, kr ? "KOSPI" : "NASDAQ", sectors[k % 4], "DIP", sharesFor.apply(big * 1.5, px), px, kr ? 500000 : 2500000);
            dip.segs.add(new Seg(n - 40, 0.0006, 0.012, 1.0, 1.0, false));
            dip.segs.add(new Seg(22, -0.011, 0.022, 1.8, 0.8, false));
            dip.segs.add(new Seg(18, 0.004, 0.015, 1.6, 1.5, true));
            dip.peTarget = 9;
        }
        // 동종·확산도용 일반 종목
        Random r = new Random(market.hashCode());
        for (int k = 0; k < 18; k++) {
            String sym = kr ? String.format("9002%02d", k * 10 % 100 + k / 10) : "DMR" + (char) ('A' + k);
            Spec g = spec(sym, (kr ? "데모일반" : "Demo General ") + (k + 1), kr ? (k % 3 == 0 ? "KOSDAQ" : "KOSPI") : (k % 2 == 0 ? "NYSE" : "NASDAQ"),
                    sectors[k % 4], "RAND", sharesFor.apply(big * (0.5 + r.nextDouble() * 1.5), px), px, kr ? 250000 + r.nextInt(300000) : 1000000 + r.nextInt(2000000));
            g.segs.add(new Seg(n - 40, 0.0004 + r.nextGaussian() * 0.0003, 0.014, 1, 1, false));
            g.segs.add(new Seg(22, -0.008, 0.02, 1.5, 0.9, false));
            g.segs.add(new Seg(18, 0.003, 0.015, 1.2, 1.1, false));
            g.peTarget = 15 + r.nextDouble() * 10;
            g.revG = r.nextGaussian() * 0.08 + 0.04;
            g.opG = r.nextGaussian() * 0.12 + 0.05;
        }
        // 지수: 상승 → 공포 급락 → 회복
        String[] idxKeys = kr ? new String[]{"KOSPI", "KOSDAQ"} : new String[]{"SPX", "NDX"};
        for (String ik : idxKeys) {
            List<Seg> segs = List.of(new Seg(n - 40, 0.0004, 0.009, 1, 1, false), new Seg(22, -0.008, 0.018, 1, 1, false), new Seg(18, 0.003, 0.011, 1, 1, false));
            index.put(ik, gen(ik.hashCode(), kr ? 2500 : 5000, 1e8, segs, false));
        }
        for (Spec s : specs.values()) {
            List<Bar> b = gen(s.sym.hashCode() * 31L, s.p0, s.baseVol, s.segs, kr);
            List<Bar> wb = new ArrayList<>();
            for (Bar x : b) wb.add(new Bar(x.date(), x.open(), x.high(), x.low(), x.close(), x.volume(), kr ? x.close() * x.volume() : null, !kr, kr ? s.shares : null));
            series.put(s.sym, wb);
            if (kr) flows.put(s.sym, genFlows(s, wb));
        }
    }

    private List<Bar> gen(long seed, double p0, double baseVol, List<Seg> segs, boolean kr) {
        Random r = new Random(seed);
        List<Bar> out = new ArrayList<>();
        double prev = p0;
        int i = 0;
        for (Seg sg : segs) {
            for (int k = 0; k < sg.len() && i < cal.size(); k++, i++) {
                double ret = sg.drift() + sg.vol() * r.nextGaussian();
                double close = prev * (1 + ret);
                double open = prev * (1 + r.nextGaussian() * sg.vol() * 0.3);
                double hi = Math.max(open, close) * (1 + Math.abs(r.nextGaussian()) * sg.vol() * 0.4);
                double lo = Math.min(open, close) * (1 - Math.abs(r.nextGaussian()) * sg.vol() * 0.4);
                if (sg.recover() && ret > -0.002 && r.nextDouble() < 0.45) {
                    lo = Math.min(lo, prev * (1 - sg.vol() * 0.8));
                    close = Math.max(close, lo + (hi - lo) * 0.85);
                    hi = Math.max(hi, close);
                }
                double v = baseVol * sg.volMult() * Math.max(0.3, 1 + 0.2 * r.nextGaussian());
                if (close > prev) v *= sg.upBias(); else if (close < prev) v /= Math.max(0.5, sg.upBias() * 0.8);
                if (kr) { close = Math.round(close); open = Math.round(open); hi = Math.round(hi); lo = Math.round(lo); }
                out.add(new Bar(cal.get(i), open, hi, lo, close, Math.round(v), null, false, null));
                prev = close;
            }
        }
        return out;
    }

    private List<FlowDay> genFlows(Spec s, List<Bar> b) {
        Random r = new Random(s.sym.hashCode() * 7L);
        List<FlowDay> out = new ArrayList<>();
        double hold = 30 + r.nextDouble() * 20;
        int accStart = DAYS - 110, accEnd = DAYS - 50;
        for (int i = 0; i < b.size(); i++) {
            double v = b.get(i).volume();
            double bias = "ACC".equals(s.kind) && i >= accStart && i < accEnd ? 0.06 : "DIP".equals(s.kind) && i >= DAYS - 18 ? 0.04 : 0.0;
            double fq = v * (bias + 0.05 * r.nextGaussian());
            double iq = v * (bias * 0.7 + 0.04 * r.nextGaussian());
            hold = Math.max(1, hold + fq / s.shares * 100);
            out.add(new FlowDay(b.get(i).date(), (double) Math.round(fq), (double) Math.round(iq), null, Math.round(hold * 100) / 100.0, b.get(i).close()));
        }
        return out;
    }

    // ---------- MarketDataProvider ----------
    @Override public String market() { return market; }
    @Override public boolean isDemo() { return true; }
    @Override public SourceStatus.Registry sources() { return reg; }

    @Override public LocalDate completedTradingDay(StringBuilder note) {
        note.append("데모 합성 달력 기준일 ").append(asOf).append(" (실제 거래일 아님)");
        return asOf;
    }

    @Override public List<Security> listUniverse(LocalDate d) {
        SourceStatus st = reg.get("DEMO-목록");
        List<Security> out = new ArrayList<>();
        for (Spec s : specs.values()) {
            Security x = new Security(market, s.sym, s.name);
            x.board = s.board;
            x.etf = s.etf; x.preferred = s.pref; x.spac = s.spac; x.nonCommon = s.nonCommon;
            Bar last = lastOn(series.get(s.sym), d);
            x.sharesOut = s.shares;
            x.marketCap = last == null ? null : last.close() * s.shares;
            x.mcapSource = "데모 합성";
            x.mcapApprox = "US".equals(market);
            out.add(x);
        }
        st.ok(false);
        st.items = out.size();
        return out;
    }

    private static Bar lastOn(List<Bar> b, LocalDate d) {
        Bar r = null;
        for (Bar x : b) { if (x.date().isAfter(d)) break; r = x; }
        return r;
    }

    @Override public Map<String, List<Bar>> bars(List<Security> secs, LocalDate d) {
        Map<String, List<Bar>> m = new LinkedHashMap<>();
        for (Security s : secs) {
            List<Bar> b = series.get(s.symbol);
            if (b == null) continue;
            List<Bar> c = new ArrayList<>();
            for (Bar x : b) if (!x.date().isAfter(d)) c.add(x);
            m.put(s.symbol, c);
        }
        reg.get("DEMO-일봉").ok(false);
        return m;
    }

    @Override public void finalizeMarketCap(List<Security> secs, Map<String, List<Bar>> bars, LocalDate d) {
        if ("US".equals(market)) for (Security s : secs) {
            List<Bar> b = bars.get(s.symbol);
            if (b != null && !b.isEmpty() && s.sharesOut != null) {
                s.marketCap = b.get(b.size() - 1).close() * s.sharesOut;
                s.mcapSource = "데모: 발행주식수×완료일 종가(근사)";
            }
        }
    }

    @Override public Map<String, List<Bar>> indexBars(LocalDate d) {
        Map<String, List<Bar>> m = new HashMap<>();
        index.forEach((k, v) -> { List<Bar> c = new ArrayList<>(); for (Bar x : v) if (!x.date().isAfter(d)) c.add(x); m.put(k, c); });
        return m;
    }

    @Override public String benchmarkKey(Security s) {
        if ("KR".equals(market)) return "KOSDAQ".equals(s.board) ? "KOSDAQ" : "KOSPI";
        return "SPX";
    }

    @Override public Map<String, List<Bar>> sentimentSeries(LocalDate d) {
        if (!"US".equals(market)) return Map.of();
        List<Bar> spx = index.get("SPX");
        List<Bar> vix = new ArrayList<>(), vix3 = new ArrayList<>();
        for (int i = 21; i < spx.size(); i++) {
            if (spx.get(i).date().isAfter(d)) break;
            double s = 0;
            for (int k = i - 19; k <= i; k++) { double rr = spx.get(k).close() / spx.get(k - 1).close() - 1; s += rr * rr; }
            double rv = Math.sqrt(s / 20) * Math.sqrt(252);
            double v = 10 + rv * 100, v3 = 14 + rv * 60;
            vix.add(Bar.of(spx.get(i).date(), v, v, v, v, 0));
            vix3.add(Bar.of(spx.get(i).date(), v3, v3, v3, v3, 0));
        }
        return Map.of("VIX", vix, "VIX3M", vix3);
    }

    @Override public boolean flowsSupported() { return "KR".equals(market); }

    @Override public Map<String, List<FlowDay>> flows(List<Security> secs, LocalDate d, int days) {
        Map<String, List<FlowDay>> m = new HashMap<>();
        if (!flowsSupported()) return m;
        for (Security s : secs) {
            List<FlowDay> f = flows.get(s.symbol);
            if (f == null) continue;
            List<FlowDay> c = new ArrayList<>();
            for (FlowDay x : f) if (!x.date().isAfter(d)) c.add(x);
            m.put(s.symbol, c.subList(Math.max(0, c.size() - days), c.size()));
        }
        return m;
    }

    @Override public void enrichSectors(List<Security> secs) {
        for (Security s : secs) { Spec sp = specs.get(s.symbol); if (sp != null) s.sector = sp.sector; }
    }

    @Override public Map<String, Fundamentals> fundamentals(List<Security> secs, LocalDate d) {
        Map<String, Fundamentals> m = new HashMap<>();
        for (Security s : secs) {
            Spec sp = specs.get(s.symbol);
            if (sp == null || sp.noFund || s.marketCap == null) continue;
            Fundamentals f = new Fundamentals();
            f.source = "데모 합성 재무";
            f.period = "데모 " + d.getYear() + " 최근 분기";
            f.periodEnd = d.minusDays(60).toString();
            double ni = s.marketCap / sp.peTarget;
            f.annualNetIncome = ni;
            f.netIncome = ni / 4;
            f.netIncomePrev = ni / 4 / (1 + sp.opG);
            f.revenue = ni * 2.5;
            f.revenuePrev = f.revenue / (1 + sp.revG);
            f.opIncome = ni / 4 * 1.3;
            f.opIncomePrev = f.opIncome / (1 + sp.opG);
            f.totalEquity = s.marketCap * 0.5;
            f.totalLiab = f.totalEquity * 0.6;
            f.capitalStock = f.totalEquity * 0.05;
            f.currentAssets = f.totalLiab * 0.9;
            f.currentLiab = f.totalLiab * 0.5;
            f.ocf = ni * 1.1;
            f.ocfPeriod = "데모 연간";
            m.put(s.symbol, f);
        }
        return m;
    }

    @Override public DisclosureCheck disclosures(Security s, LocalDate d) {
        DisclosureCheck c = new DisclosureCheck();
        c.ok = true;
        c.source = "데모 합성 공시";
        c.window = d.minusDays(90) + "~" + d;
        Spec sp = specs.get(s.symbol);
        if (sp != null) for (String[] e : sp.events) {
            LocalDate ed = cal.get(Integer.parseInt(e[0]));
            if (ed.isAfter(d) || ed.isBefore(d.minusDays(90))) continue;
            String title = e[1];
            DisclosureCheck.Level lv;
            if ("KR".equals(market)) lv = screener.engine.RiskRules.classifyKr(title);
            else { String[] fi = title.split("\\|", -1); lv = screener.engine.RiskRules.classifyUs(fi[0], fi[1]); title = fi[0] + (fi[1].isEmpty() ? "" : " items " + fi[1]); }
            c.events.add(new DisclosureCheck.Event(ed.toString(), title, lv, null, null));
        }
        c.scanned = 5 + c.events.size();
        return c;
    }

    @Override public List<Map<String, Object>> indirectEvidence(Security s, List<Bar> bars, LocalDate d) {
        if (!"US".equals(market)) return List.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "FINRA ATS 주간 거래(간접)");
        m.put("value", "데모: 미수집");
        m.put("scored", false);
        return List.of(m);
    }

    @Override public Map<String, List<Bar>> fxSeries(LocalDate d) {
        List<Bar> l = new ArrayList<>();
        for (int i = 0; i < cal.size() && !cal.get(i).isAfter(d); i++) { double v = 1350 + 30 * Math.sin(i / 20.0); l.add(Bar.of(cal.get(i), v, v, v, v, 0)); }
        return Map.of("USDKRW", l);
    }

    @Override public String fxSourceLabel() { return "데모 합성 환율"; }

    @Override public String priceSourceLabel() { return "데모 합성 일봉"; }
    @Override public String flowSourceLabel() { return "KR".equals(market) ? "데모 합성 수급" : "미국 직접 수급 없음"; }
    @Override public String fundamentalsSourceLabel() { return "데모 합성 재무"; }
    @Override public String disclosureSourceLabel() { return "데모 합성 공시"; }
}
