package screener.provider;

import screener.Config;
import screener.engine.RiskRules;
import screener.model.Bar;
import screener.model.DisclosureCheck;
import screener.model.FlowDay;
import screener.model.Fundamentals;
import screener.model.Security;
import screener.model.SourceStatus;
import screener.source.UsSources;
import screener.store.Db;
import screener.util.Http;
import screener.util.Json;
import screener.util.Log;
import screener.util.Num;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 미국 실데이터 공급자: Nasdaq Trader(상장목록), SEC(발행주식수·재무·공시·업종), Yahoo(일봉·지수),
 * Tiingo(예비 시세, 선택), Cboe(VIX), FMP(보조 시총, 선택), FINRA(ATS 간접).
 */
public final class UsLiveProvider implements MarketDataProvider {
    private static final ZoneId ET = ZoneId.of("America/New_York");
    private final Config cfg;
    private final Db db;
    private final SourceStatus.Registry reg = new SourceStatus.Registry();
    private final UsSources.NasdaqTrader nt;
    private final UsSources.Sec sec;
    private final UsSources.Yahoo yahoo;
    private final UsSources.Tiingo tiingo;
    private final UsSources.Cboe cboe;
    private final UsSources.Fmp fmp;
    private final UsSources.Finra finra;
    private Map<String, List<Bar>> indexCache;
    private final Http httpRef;

    public UsLiveProvider(Config cfg, Http http, Db db) {
        this.cfg = cfg;
        this.db = db;
        nt = new UsSources.NasdaqTrader(http, reg);
        sec = new UsSources.Sec(http, cfg.secUserAgent, reg);
        yahoo = new UsSources.Yahoo(http, reg);
        tiingo = new UsSources.Tiingo(http, cfg.tiingoKey, reg);
        cboe = new UsSources.Cboe(http, reg);
        int used = 0;
        try { used = Integer.parseInt(db.setting("fmp_used_" + LocalDate.now(), "0")); } catch (Exception ignored) { }
        fmp = new UsSources.Fmp(http, cfg.fmpKey, Math.max(0, cfg.fmpDailyBudget - used), reg);
        finra = new UsSources.Finra(http, reg);
        httpRef = http;
    }

    @Override public String market() { return "US"; }
    @Override public boolean isDemo() { return false; }
    @Override public SourceStatus.Registry sources() { return reg; }

    /** S&P 500 일봉의 최신 봉 — 당일 봉은 ET 16:15 이후에만 완료로 인정. Cboe VIX 최신일로 교차확인. */
    @Override public LocalDate completedTradingDay(StringBuilder note) throws Exception {
        List<Bar> spx = yahoo.chart("^GSPC", "1mo", Duration.ofMinutes(20));
        LocalDateTime now = LocalDateTime.now(ET);
        LocalDate last = null;
        for (Bar b : spx) {
            boolean today = b.date().equals(now.toLocalDate());
            if (b.date().isAfter(now.toLocalDate())) continue;
            if (today && (now.getHour() < 16 || (now.getHour() == 16 && now.getMinute() < 15))) continue;
            last = b.date();
        }
        if (last == null) throw new IllegalStateException("S&P 500 일봉에서 완료 거래일 확인 실패");
        note.append("Yahoo ^GSPC 일봉의 마감 완료 최신 세션 (조회 시각 ").append(now.withNano(0)).append(" ET)");
        try {
            List<Bar> vix = cboe.history("VIX");
            LocalDate vl = vix.isEmpty() ? null : vix.get(vix.size() - 1).date();
            note.append(vl == null ? "; Cboe VIX 교차확인 불가" : vl.equals(last) ? "; Cboe VIX 최신일 일치" : "; Cboe VIX 최신일 " + vl + "(불일치 — 확인 필요)");
        } catch (Exception e) {
            note.append("; Cboe VIX 교차확인 실패");
        }
        return last;
    }

    @Override public List<Security> listUniverse(LocalDate asOf) throws Exception {
        List<UsSources.NasdaqTrader.Listing> ls = nt.all();
        Map<String, String> cikBy;
        try {
            cikBy = sec.enabled() ? sec.tickers() : Map.of();
        } catch (screener.util.Http.HttpError e) {
            if (e.status != 403) throw e;
            throw new IllegalStateException(e.getMessage() + " — SEC가 이 서버의 요청을 차단함. SEC_USER_AGENT가 '앱이름/버전 본인이메일' 형식인지(따옴표·예시 주소 금지) 확인하고,"
                    + " 서버에서 curl -A \"$SEC_USER_AGENT\" https://www.sec.gov/files/company_tickers.json 으로 재현해 보세요. 차단 해제까지 약 10분 이상 걸릴 수 있습니다.", e);
        }
        if (!sec.enabled()) Log.warn("SEC_USER_AGENT 미설정 — 미국 시총·재무·공시 산출 불가");
        // 발행주식수: dei(표지) 최근 5개 분기 시점, 없으면 희석가중평균주식수(전 클래스 합산)
        Map<String, Object[]> shares = new HashMap<>();
        if (sec.enabled()) {
            for (String p : quartersBack(asOf.plusMonths(3), 6, true)) mergeLatest(shares, sec.frame("dei", "EntityCommonStockSharesOutstanding", "shares", p), "dei 표지 발행주식수");
            Map<String, Object[]> wa = new HashMap<>();
            for (String p : quartersBack(asOf, 3, false)) mergeLatest(wa, sec.frame("us-gaap", "WeightedAverageNumberOfDilutedSharesOutstanding", "shares", p), "희석가중평균주식수(전 클래스)");
            wa.forEach((k, v) -> shares.putIfAbsent(k, v));
        }
        Map<String, Object[]> px = new HashMap<>();
        if (tiingo.enabled()) {
            try { px.putAll(tiingo.iexAll()); } catch (Exception e) { Log.warn("Tiingo IEX 실패: " + e.getMessage()); }
        }
        List<Security> out = new ArrayList<>();
        for (UsSources.NasdaqTrader.Listing l : ls) {
            Security s = new Security("US", l.symbol(), l.name());
            s.board = l.exchange();
            s.etf = l.etf();
            s.testIssue = l.test();
            String n = l.name().toLowerCase();
            s.preferred = n.contains("preferred") || n.contains("depositary shares, each representing");
            s.nonCommon = n.contains(" warrant") || n.matches(".*\\bunits?\\b.*") || n.contains(" right") || n.contains(" notes ") || n.endsWith(" notes")
                    || n.contains("debenture") || n.contains("subordinated") || n.contains("% senior");
            s.spac = n.contains("acquisition corp") && !s.etf;
            if (l.finStatus() != null && !l.finStatus().isBlank() && !"N".equals(l.finStatus())) s.statusFlag = "Nasdaq 재무상태 코드 " + l.finStatus() + "(결손·지연·파산 등)";
            String ysym = UsSources.Yahoo.sym(l.symbol()).toUpperCase();
            s.cik = cikBy.get(ysym);
            Object[] sh = s.cik == null ? null : shares.get(s.cik);
            if (sh != null) s.sharesOut = (Double) sh[0];
            Object[] p = px.get(ysym);
            if (p == null) p = px.get(l.symbol().toUpperCase());
            if (p != null && s.sharesOut != null && fresh((String) p[1], asOf)) {
                s.marketCap = s.sharesOut * (Double) p[0];
                s.mcapApprox = true;
                s.mcapSource = "예비: SEC " + sh[3] + "(" + sh[1] + ") × Tiingo IEX 최근가";
            }
            boolean adr = n.contains("american depositary") || n.matches(".*\\bads\\b.*") || n.matches(".*\\badr\\b.*");
            String loc = sh != null && sh.length > 4 ? (String) sh[4] : null;
            boolean foreign = loc != null && !loc.startsWith("US");
            if (adr || foreign) s.mcapSource = "ADR(주식수-ADS 비율 미반영" + (foreign ? ", 해외 발행사 소재지 " + loc : "") + ")";
            out.add(s);
        }
        if (px.isEmpty()) prelimViaSpark(out);
        dedupeClasses(out);
        fmpFallback(out);
        // FMP로 확인되지 않은 ADR·해외 발행사: 보통주 수×ADS 가격은 단위가 맞지 않을 수 있어 시총 미확인으로 둔다(순위·필터에 쓰지 않음)
        for (Security s : out) if (s.mcapSource != null && s.mcapSource.startsWith("ADR(")) s.marketCap = null;
        return out;
    }

    private static boolean fresh(String ts, LocalDate asOf) {
        if (ts == null) return false;
        try { return !LocalDate.parse(ts.substring(0, 10)).isBefore(asOf.minusDays(7)); } catch (Exception e) { return false; }
    }

    /** Tiingo 키가 없을 때 Yahoo spark(20종목/호출)로 예비가격. */
    private void prelimViaSpark(List<Security> out) {
        List<Security> need = new ArrayList<>();
        for (Security s : out) if (s.typeExclusion() == null && s.sharesOut != null && s.marketCap == null) need.add(s);
        for (int i = 0; i < need.size(); i += 20) {
            List<Security> b = need.subList(i, Math.min(need.size(), i + 20));
            List<String> syms = new ArrayList<>();
            for (Security s : b) syms.add(s.symbol);
            try {
                Map<String, Double> p = yahoo.spark(syms);
                for (Security s : b) {
                    Double v = p.get(UsSources.Yahoo.sym(s.symbol));
                    if (v != null) { s.marketCap = s.sharesOut * v; s.mcapApprox = true; s.mcapSource = "예비: SEC 발행주식수 × Yahoo 최근가"; }
                }
            } catch (Exception e) {
                Log.warn("Yahoo spark 실패: " + e.getMessage());
            }
        }
    }

    /** 같은 CIK의 여러 클래스는 1개만 유지(가격이 가장 높은 클래스 — 주식수 기준과 정합성이 높음). */
    private static void dedupeClasses(List<Security> out) {
        Map<String, Security> keep = new HashMap<>();
        for (Security s : out) {
            if (s.cik == null || s.typeExclusion() != null) continue;
            Security k = keep.get(s.cik);
            if (k == null) { keep.put(s.cik, s); continue; }
            double pk = k.marketCap == null || k.sharesOut == null ? 0 : k.marketCap / k.sharesOut;
            double ps = s.marketCap == null || s.sharesOut == null ? 0 : s.marketCap / s.sharesOut;
            Security drop = ps > pk ? k : s;
            Security win = ps > pk ? s : k;
            keep.put(s.cik, win);
            drop.dupOf = win.symbol;
            drop.mcapSource = "동일 기업 다른 클래스(" + win.symbol + ")로 대체";
        }
    }

    /** SEC 주식수로 시총 산출이 불가하거나 ADR인 종목 중 큰 종목부터 FMP 시총(예산 내). */
    private void fmpFallback(List<Security> out) {
        if (!fmp.available()) return;
        List<Security> cand = new ArrayList<>();
        for (Security s : out) {
            if (s.typeExclusion() != null) continue;
            boolean adr = s.mcapSource != null && s.mcapSource.startsWith("ADR");
            // 크기 단서가 있는 ADR만(예비 시총 30억 달러 이상). 주식수 미확보 종목은 '시총 미확인'으로 집계
            if (adr && s.marketCap != null && s.marketCap >= 3e9) cand.add(s);
        }
        // ADR(예비값 있음) 우선, 그다음 NYSE/NASDAQ 상장 보통주
        cand.sort(Comparator.comparing((Security s) -> s.marketCap == null ? 0 : -s.marketCap));
        int used = 0;
        for (Security s : cand) {
            if (!fmp.available()) break;
            try {
                Double mc = fmp.marketCap(s.symbol);
                used++;
                if (mc != null && mc > 0) { s.marketCap = mc; s.mcapApprox = true; s.mcapSource = "FMP quote 시가총액(보조)"; s.sharesOut = null; }
            } catch (Exception e) {
                Log.warn("FMP " + s.symbol + " 실패: " + e.getMessage());
            }
        }
        try {
            String k = "fmp_used_" + LocalDate.now();
            db.setSetting(k, String.valueOf(Integer.parseInt(db.setting(k, "0")) + used));
        } catch (Exception ignored) { }
    }

    private static void mergeLatest(Map<String, Object[]> into, Map<String, Object[]> m, String label) {
        m.forEach((cik, v) -> {
            Object[] cur = into.get(cik);
            if (v[0] == null) return;
            if (cur == null || String.valueOf(v[1]).compareTo(String.valueOf(cur[1])) > 0) into.put(cik, new Object[]{v[0], v[1], v[2], label, v.length > 3 ? v[3] : null});
        });
    }

    /** 최근 n개 달력분기 frames 기간 코드. instant=true → CY2026Q3I */
    static List<String> quartersBack(LocalDate asOf, int n, boolean instant) {
        List<String> l = new ArrayList<>();
        int q = (asOf.getMonthValue() - 1) / 3; // 현재 분기 index(0..3) → 직전 완료 분기부터
        int y = asOf.getYear();
        int qq = q; // 직전 분기 번호(1..4)
        if (qq == 0) { qq = 4; y--; }
        for (int i = 0; i < n; i++) {
            l.add("CY" + y + "Q" + qq + (instant ? "I" : ""));
            qq--;
            if (qq == 0) { qq = 4; y--; }
        }
        return l;
    }

    @Override public Map<String, List<Bar>> bars(List<Security> secs, LocalDate asOf) {
        Map<String, List<Bar>> out = new LinkedHashMap<>();
        int tiingoBudget = 40;
        for (Security s : secs) {
            try {
                List<Bar> have = db.usBars(s.symbol, "9999-12-31");
                boolean needFull = have.size() < 260 || have.get(have.size() - 1).date().isBefore(asOf.minusDays(25));
                List<Bar> got = yahoo.chart(s.symbol, needFull ? "2y" : "1mo", Duration.ofHours(needFull ? 6 : 2));
                if (!needFull && !got.isEmpty()) {
                    // 분할 등으로 과거 가격이 바뀌었으면 전체 재수집
                    Bar g0 = got.get(0);
                    Bar h0 = have.stream().filter(b -> b.date().equals(g0.date())).findFirst().orElse(null);
                    if (h0 != null && Math.abs(h0.close() / g0.close() - 1) > 0.02) {
                        db.deleteUsBars(s.symbol);
                        got = yahoo.chart(s.symbol, "2y", Duration.ofHours(20));
                    }
                }
                db.upsertUsBars(s.symbol, got, "yahoo");
                List<Bar> all = db.usBars(s.symbol, asOf.toString());
                if (!all.isEmpty()) out.put(s.symbol, all);
            } catch (Exception e) {
                if (tiingo.enabled() && tiingoBudget-- > 0) {
                    try {
                        List<Bar> t = tiingo.daily(s.symbol, asOf.minusDays(560));
                        List<Bar> cut = new ArrayList<>();
                        for (Bar b : t) if (!b.date().isAfter(asOf)) cut.add(b);
                        if (!cut.isEmpty()) { out.put(s.symbol, cut); continue; }
                    } catch (Exception ignored) { }
                }
            }
        }
        reg.get(UsSources.Yahoo.NAME).items = out.size();
        return out;
    }

    @Override public void finalizeMarketCap(List<Security> secs, Map<String, List<Bar>> bars, LocalDate asOf) {
        for (Security s : secs) {
            List<Bar> b = bars.get(s.symbol);
            if (b == null || b.isEmpty() || s.sharesOut == null) continue;
            if (s.mcapSource != null && (s.mcapSource.startsWith("ADR") || s.mcapSource.startsWith("FMP"))) continue;
            Bar last = b.get(b.size() - 1);
            s.marketCap = s.sharesOut * last.close();
            s.mcapApprox = true;
            s.mcapSource = "SEC 공시 발행주식수 × " + last.date() + " 종가(근사, app-1)";
            // app-12: 시총 대비 20일 평균 거래대금이 일 0.001% 미만이면 주식수 이상치로 보고 시총 미확인 처리
            Double av = screener.engine.Indicators.avgValue(b, 20);
            if (av != null && s.marketCap > 0 && av / s.marketCap < 1e-5) {
                s.mcapSource = "SEC 주식수 이상치 의심(시총 " + Num.usd(s.marketCap) + " 대비 일 거래대금 " + Num.usd(av) + ") — 시총 미확인";
                s.marketCap = null;
            }
        }
    }

    @Override public Map<String, List<Bar>> indexBars(LocalDate asOf) {
        if (indexCache != null) return indexCache;
        Map<String, List<Bar>> m = new HashMap<>();
        for (String[] k : new String[][]{{"SPX", "^GSPC"}, {"NDX", "^NDX"}}) {
            try { m.put(k[0], yahoo.chart(k[1], "2y", Duration.ofHours(2))); } catch (Exception e) { Log.warn("지수 " + k[1] + " 실패: " + e.getMessage()); }
        }
        indexCache = m;
        return m;
    }

    @Override public String benchmarkKey(Security s) { return "SPX"; }

    @Override public VolObs volatility(LocalDate asOf) {
        try { return vixObs(cboe.history("VIX"), asOf, "Cboe VIX_History.csv"); }
        catch (Exception e) { return new VolObs("VIX", null, null, null, UsSources.Cboe.NAME, "수집 실패: " + e.getMessage()); }
    }

    /** 기준일 이하 최신 VIX 와 전일 대비. 기준일보다 이전 값이면 날짜를 표시(공급원 갱신 지연). */
    public static VolObs vixObs(List<Bar> vix, LocalDate asOf, String src) {
        Bar last = null, prev = null;
        for (Bar b : vix) { if (b.date().isAfter(asOf)) break; prev = last; last = b; }
        if (last == null) return new VolObs("VIX", null, null, null, src, "기준일 이하 자료 없음");
        return new VolObs("VIX", last.close(), prev == null ? null : last.close() - prev.close(), last.date().toString(), src,
                last.date().equals(asOf) ? null : "공급원 최신일 " + last.date() + "(기준일 " + asOf + "보다 이전)");
    }

    @Override public Map<String, List<Bar>> sentimentSeries(LocalDate asOf) {
        Map<String, List<Bar>> m = new HashMap<>();
        for (String k : new String[]{"VIX", "VIX3M"}) {
            try { m.put(k, cboe.history(k)); } catch (Exception e) { Log.warn("Cboe " + k + " 실패: " + e.getMessage()); }
        }
        return m;
    }

    @Override public boolean flowsSupported() { return false; }

    @Override public Map<String, List<FlowDay>> flows(List<Security> secs, LocalDate asOf, int days) { return Map.of(); }

    @Override public void enrichSectors(List<Security> secs) {
        if (!sec.enabled()) return;
        for (Security s : secs) {
            try {
                String[] c = db.sector("US", s.symbol, 90);
                if (c == null && s.cik != null) {
                    Map<String, Object> sub = sec.submissions(s.cik);
                    String sic = Json.str(sub, "sic");
                    if (sic != null && sic.length() >= 2) {
                        db.setSector("US", s.symbol, "SIC" + sic.substring(0, 2), Json.str(sub, "sicDescription"), UsSources.Sec.NAME);
                        c = new String[]{"SIC" + sic.substring(0, 2), Json.str(sub, "sicDescription")};
                    }
                }
                if (c != null) { s.sector = c[0]; s.sectorName = c[1]; }
            } catch (Exception e) {
                Log.warn("SIC " + s.symbol + " 실패: " + e.getMessage());
            }
        }
    }

    @Override public Map<String, Fundamentals> fundamentals(List<Security> secs, LocalDate asOf) {
        Map<String, Fundamentals> out = new HashMap<>();
        if (!sec.enabled()) return out;
        List<String> qs = quartersBack(asOf, 3, false), qi = quartersBack(asOf, 3, true);
        Map<String, Map<String, Object[]>> dur = new HashMap<>();
        for (String q : qs) {
            String prev = "CY" + (Integer.parseInt(q.substring(2, 6)) - 1) + q.substring(6);
            for (String tag : new String[]{"Revenues", "RevenueFromContractWithCustomerExcludingAssessedTax", "OperatingIncomeLoss", "NetIncomeLoss"}) {
                dur.put(tag + "|" + q, sec.frame("us-gaap", tag, "USD", q));
                dur.put(tag + "|" + prev, sec.frame("us-gaap", tag, "USD", prev));
            }
        }
        Map<String, Map<String, Object[]>> inst = new HashMap<>();
        for (String q : qi) for (String tag : new String[]{"Liabilities", "StockholdersEquity", "AssetsCurrent", "LiabilitiesCurrent"})
            inst.put(tag + "|" + q, sec.frame("us-gaap", tag, "USD", q));
        int y = asOf.getYear();
        Map<String, Object[]> ocf = sec.frame("us-gaap", "NetCashProvidedByUsedInOperatingActivities", "USD", "CY" + (y - 1));
        Map<String, Object[]> ocf2 = sec.frame("us-gaap", "NetCashProvidedByUsedInOperatingActivities", "USD", "CY" + (y - 2));
        Map<String, Object[]> nia = sec.frame("us-gaap", "NetIncomeLoss", "USD", "CY" + (y - 1));
        Map<String, Object[]> nia2 = sec.frame("us-gaap", "NetIncomeLoss", "USD", "CY" + (y - 2));
        for (Security s : secs) {
            if (s.cik == null) continue;
            String cik = s.cik;
            Fundamentals f = new Fundamentals();
            f.source = "SEC XBRL frames(달력 분기 정렬)";
            for (String q : qs) {
                Object[] rv = get(dur, "Revenues|" + q, cik);
                if (rv == null) rv = get(dur, "RevenueFromContractWithCustomerExcludingAssessedTax|" + q, cik);
                Object[] op = get(dur, "OperatingIncomeLoss|" + q, cik);
                Object[] ni = get(dur, "NetIncomeLoss|" + q, cik);
                if (rv == null && op == null && ni == null) continue;
                String prev = "CY" + (Integer.parseInt(q.substring(2, 6)) - 1) + q.substring(6);
                Object[] rvp = get(dur, "Revenues|" + prev, cik);
                if (rvp == null || rv == null) rvp = get(dur, "RevenueFromContractWithCustomerExcludingAssessedTax|" + prev, cik);
                f.period = q;
                f.revenue = v(rv); f.revenuePrev = v(rvp);
                f.opIncome = v(op); f.opIncomePrev = v(get(dur, "OperatingIncomeLoss|" + prev, cik));
                f.netIncome = v(ni); f.netIncomePrev = v(get(dur, "NetIncomeLoss|" + prev, cik));
                Object[] any = rv != null ? rv : op != null ? op : ni;
                f.periodEnd = (String) any[1];
                f.filedOrReceipt = (String) any[2];
                break;
            }
            for (String q : qi) {
                Object[] tl = get(inst, "Liabilities|" + q, cik), te = get(inst, "StockholdersEquity|" + q, cik);
                if (tl == null && te == null) continue;
                f.totalLiab = v(tl); f.totalEquity = v(te);
                f.currentAssets = v(get(inst, "AssetsCurrent|" + q, cik));
                f.currentLiab = v(get(inst, "LiabilitiesCurrent|" + q, cik));
                if (f.periodEnd == null) f.periodEnd = (String) (tl != null ? tl : te)[1];
                break;
            }
            Object[] o = ocf.get(cik) != null ? ocf.get(cik) : ocf2.get(cik);
            if (o != null) { f.ocf = v(o); f.ocfPeriod = "연간(" + o[1] + " 종료)"; }
            Object[] a = nia.get(cik) != null ? nia.get(cik) : nia2.get(cik);
            if (a != null) { f.annualNetIncome = v(a); f.earningsNote = "직전 회계연도 순이익(" + a[1] + " 종료)"; }
            if (f.period == null && f.totalLiab == null && f.totalEquity == null) continue;
            if (f.periodEnd != null && LocalDate.parse(f.periodEnd).isAfter(asOf)) continue;
            out.put(s.symbol, f);
        }
        reg.get(UsSources.Sec.NAME).items = out.size();
        return out;
    }

    private static Object[] get(Map<String, Map<String, Object[]>> m, String k, String cik) {
        Map<String, Object[]> x = m.get(k);
        return x == null ? null : x.get(cik);
    }

    private static Double v(Object[] o) { return o == null ? null : (Double) o[0]; }

    @Override public DisclosureCheck disclosures(Security s, LocalDate asOf) {
        if (!sec.enabled()) return DisclosureCheck.failed(UsSources.Sec.NAME, "SEC_USER_AGENT 미설정");
        if (s.cik == null) return DisclosureCheck.failed(UsSources.Sec.NAME, "CIK 미확인");
        try {
            Map<String, Object> sub = sec.submissions(s.cik);
            Map<String, Object> rec = Json.obj(Json.obj(sub.get("filings")).get("recent"));
            List<Object> form = Json.arr(rec.get("form")), fd = Json.arr(rec.get("filingDate")), items = Json.arr(rec.get("items")),
                    acc = Json.arr(rec.get("accessionNumber")), doc = Json.arr(rec.get("primaryDocument"));
            DisclosureCheck c = new DisclosureCheck();
            c.ok = true;
            c.source = "SEC EDGAR submissions";
            LocalDate from = asOf.minusDays(90);
            c.window = from + "~" + asOf;
            int form4 = 0, k8 = 0;
            for (int i = 0; i < form.size(); i++) {
                LocalDate d = LocalDate.parse((String) fd.get(i));
                if (d.isAfter(asOf)) continue;
                if (d.isBefore(from)) break;
                c.scanned++;
                String f = (String) form.get(i), it = items.get(i) == null ? "" : items.get(i).toString();
                if ("4".equals(f)) form4++;
                if ("8-K".equals(f)) k8++;
                DisclosureCheck.Level lv = RiskRules.classifyUs(f, it);
                if (lv == DisclosureCheck.Level.INFO) continue;
                String url = "https://www.sec.gov/Archives/edgar/data/" + Long.parseLong(s.cik) + "/" + acc.get(i).toString().replace("-", "") + "/" + doc.get(i);
                c.events.add(new DisclosureCheck.Event(d.toString(), f + (it.isEmpty() ? "" : " items " + it), lv, RiskRules.usRuleOf(f, it), url));
            }
            c.indirectNote = "Form 4(내부자 거래 보고) " + form4 + "건·8-K " + k8 + "건/90일 — 매수·매도 방향 미분석. 13F 기관 보유: 미수집(분기 보고, 최대 45일 지연 — 실시간 매집 신호 아님)";
            return c;
        } catch (Exception e) {
            return DisclosureCheck.failed(UsSources.Sec.NAME, e.getMessage());
        }
    }

    @Override public List<Map<String, Object>> indirectEvidence(Security s, List<Bar> bars, LocalDate asOf) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object[] w = finra.weekly(s.symbol, asOf);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "FINRA ATS 주간 거래(간접)");
        m.put("scored", false);
        if (w == null) m.put("value", "최근 6주 내 공개 자료 없음 또는 조회 실패");
        else {
            LocalDate ws = (LocalDate) w[0];
            double tot = 0;
            for (Bar b : bars) if (!b.date().isBefore(ws) && b.date().isBefore(ws.plusDays(7))) tot += b.volume();
            Double ats = (Double) w[1];
            m.put("value", "주간 " + ws + " ATS " + (ats == null ? "-" : String.format("%,.0f주", ats)) + (tot > 0 && ats != null ? String.format(" (전체 거래량 대비 %.1f%%)", ats / tot * 100) : "")
                    + ", 공개일 " + w[3] + " — 매수 주체·방향 정보 없음, 기관 매수로 단정 금지");
        }
        out.add(m);
        Map<String, Object> f13 = new LinkedHashMap<>();
        f13.put("name", "SEC 13F 기관 보유 변화(간접)");
        f13.put("value", "미수집 — 분기말 기준·최대 45일 지연 공개, 실시간 매집 신호로 사용하지 않음");
        f13.put("scored", false);
        out.add(f13);
        return out;
    }

    private String fxLabel = "환율 미확보";
    private Map<String, List<Bar>> fxCache;

    /** 1순위 한국은행 ECOS 원/미국달러 매매기준율(731Y001, 공식), 실패·미설정 시 Yahoo KRW=X(비공식). */
    @Override public Map<String, List<Bar>> fxSeries(LocalDate asOf) {
        if (fxCache != null) return fxCache;
        if (cfg.ecosKey != null) {
            SourceStatus st = reg.get("한국은행 ECOS");
            try {
                String url = "https://ecos.bok.or.kr/api/StatisticSearch/" + cfg.ecosKey + "/json/kr/1/1000/731Y001/D/"
                        + asOf.minusDays(800).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "/" + asOf.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "/0000001";
                Map<String, Object> o = Json.obj(Json.parse(httpRef.getText(st, url, null, Duration.ofHours(6))));
                Map<String, Object> ss = Json.obj(o.get("StatisticSearch"));
                if (ss == null) throw new IllegalStateException("ECOS 응답 오류: " + Log.redact(String.valueOf(o.get("RESULT"))));
                List<Bar> l = new ArrayList<>();
                for (Object x : Json.arr(ss.get("row"))) {
                    Map<String, Object> r = Json.obj(x);
                    Double v = Num.parse(Json.str(r, "DATA_VALUE"));
                    if (v != null) l.add(Bar.of(LocalDate.parse(Json.str(r, "TIME"), java.time.format.DateTimeFormatter.BASIC_ISO_DATE), v, v, v, v, 0));
                }
                l.sort(java.util.Comparator.comparing(Bar::date));
                if (!l.isEmpty()) {
                    st.items = l.size();
                    fxLabel = "한국은행 ECOS 원/미국달러 매매기준율(731Y001, 한국 영업일 기준)";
                    return fxCache = Map.of("USDKRW", l);
                }
            } catch (Exception e) {
                Log.warn("ECOS 환율 실패 — Yahoo로 대체: " + Log.redact(String.valueOf(e.getMessage())));
            }
        }
        try {
            fxLabel = "Yahoo Finance KRW=X 일별 종가(비공식, ECOS 미사용)";
            return fxCache = Map.of("USDKRW", yahoo.chart("KRW=X", "2y", Duration.ofHours(6)));
        } catch (Exception e) {
            Log.warn("환율 KRW=X 실패: " + e.getMessage());
            fxLabel = "환율 미확보";
            return fxCache = Map.of();
        }
    }

    @Override public String fxSourceLabel() { return fxLabel; }

    private screener.source.NaverNewsSource news;

    @Override public screener.source.NaverNewsSource newsSource() {
        if (news == null) news = new screener.source.NaverNewsSource(httpRef, cfg.naverId, cfg.naverSecret, reg);
        return news;
    }

    @Override public String priceSourceLabel() { return "Yahoo Finance 일봉(비공식, 분할 조정)"; }
    @Override public String flowSourceLabel() { return "미국 종목별 투자자 순매수 직접 자료 없음"; }
    @Override public String fundamentalsSourceLabel() { return "SEC XBRL frames"; }
    @Override public String disclosureSourceLabel() { return "SEC EDGAR submissions"; }

    public static String money(Double v) { return Num.usd(v); }
}
