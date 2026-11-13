package screener.provider;

import screener.Config;
import screener.model.Bar;
import screener.model.DisclosureCheck;
import screener.model.FlowDay;
import screener.model.Fundamentals;
import screener.model.Security;
import screener.model.SourceStatus;
import screener.source.DartSource;
import screener.source.KrxSource;
import screener.source.NaverSource;
import screener.store.Db;
import screener.util.Http;
import screener.util.Json;
import screener.util.Log;
import screener.util.Num;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 한국 실데이터 공급자: KRX OpenAPI(상장 종목·일봉·시총·거래대금), Naver(수급·업종·지수 일봉), OpenDART(재무·공시).
 */
public final class KrLiveProvider implements MarketDataProvider {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Config cfg;
    private final Db db;
    private final SourceStatus.Registry reg = new SourceStatus.Registry();
    private final KrxSource krx;
    private final NaverSource naver;
    private final DartSource dart;
    private Map<String, String> corpCodes;
    private final Http httpRef;

    public KrLiveProvider(Config cfg, Http http, Db db) {
        this.cfg = cfg;
        this.db = db;
        this.krx = new KrxSource(http, cfg.krxKey, reg);
        this.naver = new NaverSource(http, reg);
        this.dart = new DartSource(http, cfg.dartKey, reg);
        this.httpRef = http;
    }

    @Override public String market() { return "KR"; }
    @Override public boolean isDemo() { return false; }
    @Override public SourceStatus.Registry sources() { return reg; }

    /** KRX 일별매매정보가 실제로 존재하는 최신 영업일. 장 마감(15:30) 후 자료 생성 시간을 고려해 16시 이전에는 전일부터 확인. */
    @Override public LocalDate completedTradingDay(StringBuilder note) throws Exception {
        if (!krx.enabled()) throw new IllegalStateException("KRX_API_KEY 미설정 — 한국 상장 목록/일봉 수집 불가");
        LocalDateTime now = LocalDateTime.now(KST);
        LocalDate d = now.getHour() < 16 ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        for (int i = 0; i < 15; i++, d = d.minusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            if (ensureDate(d)) {
                note.append("KRX 유가증권 일별매매정보에 자료가 존재하는 최신 영업일 (조회 시각 ").append(now.withNano(0)).append(" KST)");
                return d;
            }
        }
        throw new IllegalStateException("최근 15일 내 KRX 자료가 있는 영업일을 찾지 못함");
    }

    /** 해당 일자 KRX 자료 확보(DB 캐시). 반환: 개장일 여부. */
    private boolean ensureDate(LocalDate d) throws Exception {
        String ds = d.toString();
        String stv = db.krxDateStatus(ds);
        boolean recent = !d.isBefore(LocalDate.now(KST).minusDays(4));
        if (stv != null) {
            String[] p = stv.split("\\|");
            if ("OPEN".equals(p[0])) return true;
            if ("EMPTY".equals(p[0]) && (!recent || LocalDateTime.parse(p[1]).isAfter(LocalDateTime.now().minusHours(1)))) return false;
        }
        List<Map<String, Object>> stk = krx.daily("STK", d);
        if (stk.isEmpty()) { db.setKrxDate(ds, "EMPTY", 0); return false; }
        List<Map<String, Object>> ksq = krx.daily("KSQ", d);
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> r : stk) rows.add(row(ds, r));
        for (Map<String, Object> r : ksq) rows.add(row(ds, r));
        db.insertKrxRows(rows);
        db.setKrxDate(ds, "OPEN", rows.size());
        return true;
    }

    private static Object[] row(String ds, Map<String, Object> r) {
        return new Object[]{ds, Json.str(r, "ISU_CD"), Json.str(r, "MKT_NM"), Json.str(r, "ISU_NM"), Json.str(r, "SECT_TP_NM"),
                Num.parse(Json.str(r, "TDD_OPNPRC")), Num.parse(Json.str(r, "TDD_HGPRC")), Num.parse(Json.str(r, "TDD_LWPRC")),
                Num.parse(Json.str(r, "TDD_CLSPRC")), Num.parse(Json.str(r, "ACC_TRDVOL")), Num.parse(Json.str(r, "ACC_TRDVAL")),
                Num.parse(Json.str(r, "MKTCAP")), Num.parse(Json.str(r, "LIST_SHRS"))};
    }

    /** 이력 확보: 완료일부터 영업일을 거슬러 krHistoryDays개 개장일을 채운다(이미 받은 일자는 재호출하지 않음). */
    private void ensureHistory(LocalDate asOf) {
        int need = cfg.krHistoryDays;
        int have = 0, fails = 0;
        LocalDate d = asOf;
        int guard = (int) (need * 1.6) + 20;
        while (have < need && guard-- > 0) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                try { if (ensureDate(d)) have++; }
                catch (Exception e) { fails++; Log.warn("KRX " + d + " 수집 실패: " + e.getMessage()); if (fails > 10) break; }
            }
            d = d.minusDays(1);
        }
        reg.get(KrxSource.NAME).note = "개장일 " + have + "/" + need + "일 확보 (DB 캐시 포함)";
    }

    @Override public List<Security> listUniverse(LocalDate asOf) throws Exception {
        ensureHistory(asOf);
        List<Security> out = new ArrayList<>();
        for (Map<String, Object> r : db.krxRowsOn(asOf.toString())) {
            String code = (String) r.get("code");
            String name = (String) r.get("name");
            Security s = new Security("KR", code, name);
            s.board = (String) r.get("market");
            s.marketCap = r.get("mcap") == null ? null : ((Number) r.get("mcap")).doubleValue();
            s.mcapSource = "KRX 일별매매정보 시가총액(" + asOf + ")";
            s.sharesOut = r.get("shares") == null ? null : ((Number) r.get("shares")).doubleValue();
            String sect = r.get("sect") == null ? "" : (String) r.get("sect");
            s.preferred = !code.endsWith("0") || name.matches(".*우[A-Z]?$") || name.endsWith("우(전환)");
            s.spac = name.contains("스팩") || sect.contains("SPAC");
            if (sect.contains("관리")) s.statusFlag = "관리종목(" + sect + ")";
            else if (sect.contains("투자주의환기")) s.statusFlag = "투자주의환기종목";
            double open = r.get("open") == null ? 0 : ((Number) r.get("open")).doubleValue();
            double vol = r.get("volume") == null ? 0 : ((Number) r.get("volume")).doubleValue();
            if (open == 0 && vol == 0 && s.statusFlag == null) s.statusFlag = "당일 거래 없음(거래정지 가능)";
            if ("KOSPI".equals(s.board)) s.statusUnknown = true; // 유가증권 관리종목 여부는 이 API가 제공하지 않음 → 공시 스캔으로 확인
            out.add(s);
        }
        reg.get(KrxSource.NAME).items = out.size();
        return out;
    }

    @Override public Map<String, List<Bar>> bars(List<Security> secs, LocalDate asOf) {
        try {
            List<String> dates = db.krxOpenDates(asOf.toString(), cfg.krHistoryDays);
            String from = dates.isEmpty() ? asOf.toString() : dates.get(dates.size() - 1);
            List<String> codes = new ArrayList<>();
            for (Security s : secs) codes.add(s.symbol);
            return db.krxBars(codes, from, asOf.toString());
        } catch (Exception e) {
            reg.get(KrxSource.NAME).fail("DB 일봉 조회 실패: " + e.getMessage());
            return Map.of();
        }
    }

    @Override public void finalizeMarketCap(List<Security> secs, Map<String, List<Bar>> bars, LocalDate asOf) { /* KRX 시총 확정값 사용 */ }

    @Override public Map<String, List<Bar>> indexBars(LocalDate asOf) {
        Map<String, List<Bar>> m = new HashMap<>();
        for (String[] k : new String[][]{{"KOSPI", "kospi", "코스피"}, {"KOSDAQ", "kosdaq", "코스닥"}}) {
            try {
                List<Bar> b = naver.indexChart(k[0], cfg.krHistoryDays + 420);
                if (b.isEmpty()) continue;
                m.put(k[0], b);
                Double krxClose = krx.indexClose(k[1], k[2], asOf);
                Bar last = null;
                for (Bar x : b) if (!x.date().isAfter(asOf)) last = x;
                String chk = krxClose == null || last == null ? "KRX 교차확인 불가" :
                        Math.abs(last.close() / krxClose - 1) < 0.001 ? "KRX 종가와 일치(" + krxClose + ")" : "KRX 종가 " + krxClose + " vs Naver " + last.close() + " 불일치";
                SourceStatus st = reg.get(NaverSource.NAME);
                st.note = (st.note == null ? "" : st.note + "; ") + k[0] + " 지수 " + chk;
            } catch (Exception e) {
                Log.warn("지수 " + k[0] + " 수집 실패: " + e.getMessage());
            }
        }
        return m;
    }

    @Override public VolObs volatility(LocalDate asOf) {
        try {
            Double[] v = krx.vkospi(asOf);
            if (v == null || v[0] == null) return new VolObs("VKOSPI", null, null, asOf.toString(), KrxSource.NAME, "응답에 '코스피 200 변동성지수' 없음");
            return new VolObs("VKOSPI(코스피 200 변동성지수)", v[0], v[1], asOf.toString(), KrxSource.NAME + " 파생상품지수", null);
        } catch (Exception e) {
            return new VolObs("VKOSPI", null, null, asOf.toString(), KrxSource.NAME, "수집 실패: " + e.getMessage());
        }
    }

    @Override public String benchmarkKey(Security s) { return "KOSDAQ".equals(s.board) ? "KOSDAQ" : "KOSPI"; }

    @Override public Map<String, List<Bar>> sentimentSeries(LocalDate asOf) { return Map.of(); }

    @Override public boolean flowsSupported() { return true; }

    /** 종목별 투자자 동향. DB에 누적하고 부족분만 요청한다. */
    @Override public Map<String, List<FlowDay>> flows(List<Security> secs, LocalDate asOf, int days) {
        Map<String, List<FlowDay>> out = new LinkedHashMap<>();
        int fails = 0;
        for (Security s : secs) {
            try {
                List<FlowDay> have = db.flows(s.symbol, asOf.toString());
                boolean fresh = !have.isEmpty() && !have.get(have.size() - 1).date().isBefore(asOf);
                if (!fresh) {
                    db.upsertFlows(s.symbol, naver.trend(s.symbol, null, 60));
                    have = db.flows(s.symbol, asOf.toString());
                }
                int pages = 0;
                while (!have.isEmpty() && have.size() < days && pages++ < 4) {
                    LocalDate oldest = have.get(0).date();
                    List<FlowDay> chunk = naver.trend(s.symbol, oldest, 60);
                    if (chunk.isEmpty()) break;
                    db.upsertFlows(s.symbol, chunk);
                    have = db.flows(s.symbol, asOf.toString());
                    if (!have.get(0).date().isBefore(oldest) || chunk.size() < 60) break;
                }
                if (!have.isEmpty()) out.put(s.symbol, have.subList(Math.max(0, have.size() - Math.max(days, 70)), have.size()));
            } catch (Exception e) {
                fails++;
                if (fails <= 3) Log.warn("수급 " + s.symbol + " 실패: " + e.getMessage());
            }
        }
        reg.get(NaverSource.NAME).items += out.size();
        return out;
    }

    @Override public void enrichSectors(List<Security> secs) {
        for (Security s : secs) {
            try {
                String[] c = db.sector("KR", s.symbol, 90);
                if (c == null) {
                    String ic = naver.industryCode(s.symbol);
                    if (ic == null) continue;
                    db.setSector("KR", s.symbol, "N" + ic, null, NaverSource.NAME);
                    c = new String[]{"N" + ic, null};
                }
                s.sector = c[0];
            } catch (Exception e) {
                Log.warn("업종 " + s.symbol + " 실패: " + e.getMessage());
            }
        }
    }

    @Override public Map<String, Fundamentals> fundamentals(List<Security> secs, LocalDate asOf) {
        Map<String, Fundamentals> out = new HashMap<>();
        if (!dart.enabled()) return out;
        try {
            if (corpCodes == null) corpCodes = dart.corpCodes();
        } catch (Exception e) {
            reg.get(DartSource.NAME).note = "corpCode 수집 실패: " + e.getMessage();
            return out;
        }
        Map<String, String> pending = new LinkedHashMap<>();
        for (Security s : secs) {
            String cc = corpCodes.get(s.symbol);
            if (cc != null) { s.corpCode = cc; pending.put(s.symbol, cc); }
        }
        // 직전 연간(TTM 계산용)
        Map<String, Map<String, String[]>> annualPrev = new HashMap<>();
        int y = asOf.getYear();
        batches(new ArrayList<>(pending.values()), 100).forEach(b -> {
            try { annualPrev.putAll(dart.multiAccounts(b, y - 1, "11011")); } catch (Exception e) { Log.warn("DART 연간 실패: " + e.getMessage()); }
        });
        for (String[] rp : DartSource.reportOrder(asOf)) {
            if (pending.isEmpty()) break;
            int yr = Integer.parseInt(rp[0]);
            Map<String, Map<String, String[]>> got = new HashMap<>();
            if (yr == y - 1 && "11011".equals(rp[1])) got.putAll(annualPrev);
            else for (List<String> b : batches(new ArrayList<>(pending.values()), 100)) {
                try { got.putAll(dart.multiAccounts(b, yr, rp[1])); } catch (Exception e) { Log.warn("DART " + yr + "/" + rp[1] + " 실패: " + e.getMessage()); }
            }
            for (Map.Entry<String, Map<String, String[]>> e : got.entrySet()) {
                if (!pending.containsKey(e.getKey())) continue;
                Fundamentals f = DartSource.toFundamentals(e.getValue(), "11011".equals(rp[1]) ? null : annualPrev.get(e.getKey()), yr, rp[1]);
                if (f.periodEnd != null && LocalDate.parse(f.periodEnd).isAfter(asOf)) continue; // 미래 자료 차단
                out.put(e.getKey(), f);
                pending.remove(e.getKey());
            }
        }
        reg.get(DartSource.NAME).items = out.size();
        if (!pending.isEmpty()) reg.get(DartSource.NAME).note = "주요계정 미확보 " + pending.size() + "개사(최근 5개 보고서 기준)";
        return out;
    }

    static List<List<String>> batches(List<String> l, int n) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i += n) out.add(l.subList(i, Math.min(l.size(), i + n)));
        return out;
    }

    @Override public DisclosureCheck disclosures(Security s, LocalDate asOf) {
        if (!dart.enabled()) return DisclosureCheck.failed(DartSource.NAME, "DART_API_KEY 미설정");
        if (s.corpCode == null && corpCodes != null) s.corpCode = corpCodes.get(s.symbol);
        if (s.corpCode == null) return DisclosureCheck.failed(DartSource.NAME, "DART 고유번호 미확인");
        DisclosureCheck c = dart.disclosures(s.corpCode, asOf);
        if (c.ok && s.statusUnknown) c.indirectNote = "유가증권 관리종목·거래정지 여부는 최근 90일 공시 키워드로 확인";
        return c;
    }

    @Override public List<Map<String, Object>> indirectEvidence(Security s, List<Bar> bars, LocalDate asOf) { return List.of(); }

    /** KRX 원자료 시가: 0 이면 해당일 거래 없음(거래정지 등)으로 시가 미확인 처리. */
    @Override public OpenObs openOn(Security s, LocalDate d, List<Bar> bars) {
        try {
            Map<String, Object> r = db.krxRow(d.toString(), s.symbol);
            if (r == null) return new OpenObs(null, null, null, KrxSource.NAME, "KRX 체결일 자료에 종목 없음(상장폐지·거래정지 확인 필요)");
            double open = r.get("open") == null ? 0 : ((Number) r.get("open")).doubleValue();
            Double vol = r.get("volume") == null ? null : ((Number) r.get("volume")).doubleValue();
            Double val = r.get("value") == null ? null : ((Number) r.get("value")).doubleValue();
            if (open <= 0) return new OpenObs(null, vol, val, KrxSource.NAME, "KRX 시가 0 — 거래정지 또는 무거래");
            return new OpenObs(open, vol, val, KrxSource.NAME + " 일별매매정보 시가", null);
        } catch (Exception e) {
            return new OpenObs(null, null, null, KrxSource.NAME, "시가 조회 실패: " + e.getMessage());
        }
    }

    private screener.source.NaverNewsSource news;

    @Override public screener.source.NaverNewsSource newsSource() {
        if (news == null) news = new screener.source.NaverNewsSource(httpRef, cfg.naverId, cfg.naverSecret, reg);
        return news;
    }

    @Override public String priceSourceLabel() { return "KRX OpenAPI 일별매매정보(원주가·실제 거래대금)"; }
    @Override public String flowSourceLabel() { return "Naver 증권 투자자별 매매동향(비공식, 수량)"; }
    @Override public String fundamentalsSourceLabel() { return "OpenDART 다중회사 주요계정"; }
    @Override public String disclosureSourceLabel() { return "OpenDART 공시검색"; }

    public static Map<String, Integer> countBoards(List<Security> l) {
        Map<String, Integer> m = new TreeMap<>();
        for (Security s : l) m.merge(String.valueOf(s.board), 1, Integer::sum);
        return m;
    }
}
