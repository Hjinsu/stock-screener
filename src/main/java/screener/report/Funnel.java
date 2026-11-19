package screener.report;

import screener.store.Db;
import screener.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 역발상 진입 단계별 생존 집계(최근 N 거래일, 종목·일 합계): 유니버스 → 가격·유동성 → 재무 검증 → OIS → MSI → 1차 진입(반전) 조건 → 자금 한도.
 * 각 단계에서 '조건 미충족'과 '데이터 부족(평가 불가)'을 구분하고 주요 사유를 센다. 원본(MSI≤15)·실험(MSI≤20) 계좌를 나란히 보여준다.
 */
public final class Funnel {
    private Funnel() { }

    static final String[] STAGES = {"가격·유동성(시세 최신성)", "재무 검증", "OIS(관심 이상)", "MSI(1차 기준)", "1차 진입 조건(60일 낙폭 ≤ -15%)", "자금 한도(체결)"};

    static final class Row {
        int pass, fail, nodata;
        final Map<String, Integer> why = new TreeMap<>();
        void fail(String r) { fail++; why.merge("미충족: " + r, 1, Integer::sum); }
        void nodata(String r) { nodata++; why.merge("부족: " + r, 1, Integer::sum); }
    }

    public static String build(Db db, String market, int days) throws Exception {
        List<Map<String, Object>> ds = db.query("SELECT DISTINCT as_of FROM scores WHERE market=? AND model='OIS' ORDER BY as_of DESC LIMIT " + days, market);
        if (ds.isEmpty()) return "[" + market + "] OIS 기록 없음";
        String from = (String) ds.get(ds.size() - 1).get("as_of"), to = (String) ds.get(0).get("as_of");
        Map<String, Row[]> rows = new LinkedHashMap<>();
        int[] thr = {15, 20};
        String[] acct = {market + "-" + ("KR".equals(market) ? "KRW" : "USD"), market + "-" + ("KR".equals(market) ? "KRW" : "USD") + "-EXP20"};
        for (int v = 0; v < 2; v++) { Row[] r = new Row[STAGES.length]; for (int i = 0; i < r.length; i++) r[i] = new Row(); rows.put(acct[v], r); }
        long listed = 0, common = 0, uni = 0, barsFail = 0, pruned = 0;
        int runs = 0;
        for (Map<String, Object> dr : ds) {
            String d = (String) dr.get("as_of");
            List<Map<String, Object>> run = db.query("SELECT summary_json FROM runs WHERE market=? AND as_of=? AND status IN ('OK','PARTIAL') ORDER BY id DESC LIMIT 1", market, d);
            if (!run.isEmpty() && run.get(0).get("summary_json") != null) {
                Map<String, Object> sm = Json.obj(Json.parse((String) run.get(0).get("summary_json")));
                listed += n(sm.get("listingsTotal")); common += n(sm.get("commonStocks")); uni += n(sm.get("oisUniverse")); barsFail += n(sm.get("barsFailed"));
                runs++;
            }
            List<Map<String, Object>> m = db.query("SELECT score FROM msi WHERE market=? AND as_of=?", market, d);
            Double msi = m.isEmpty() || m.get(0).get("score") == null ? null : ((Number) m.get(0).get("score")).doubleValue();
            List<Map<String, Object>> cards = db.query("SELECT status, card_json FROM scores WHERE market=? AND model='OIS' AND as_of=?", market, d);
            for (Map<String, Object> cr : cards) {
                if (cr.get("card_json") == null) { pruned++; continue; } // 오래된 '보류' 상세는 정리됨
                Map<String, Object> c = Json.obj(Json.parse((String) cr.get("card_json")));
                Map<String, Object> info = Json.obj(c.get("info"));
                String st = (String) c.get("status");
                String fin = "UNCONFIRMED", dis = "UNCONFIRMED";
                for (Object o : Json.arr(c.get("mandatory"))) {
                    Map<String, Object> mr = Json.obj(o);
                    if ("재무 필수 검증".equals(mr.get("name"))) fin = (String) mr.get("status");
                    if ("공시 위험 스캔".equals(mr.get("name"))) dis = (String) mr.get("status");
                }
                double cov = ((Number) c.get("coverage")).doubleValue();
                Object dd = info.get("drawdown60");
                for (int v = 0; v < 2; v++) {
                    Row[] r = rows.get(acct[v]);
                    if (!d.equals(info.get("lastBarDate"))) { r[0].nodata("기준일 시세 없음·지연"); continue; }
                    r[0].pass++;
                    if ("BLOCK".equals(fin)) { r[1].fail("재무 중대 위험"); continue; }
                    if ("REVIEW".equals(fin)) { r[1].fail("재무 검토 필요(유동성 등)"); continue; }
                    if (!"CONFIRMED".equals(fin)) { r[1].nodata("재무제표 미확보·지연"); continue; }
                    r[1].pass++;
                    if ("PRIORITY".equals(st) || "INTEREST".equals(st)) r[2].pass++;
                    else if ("EXCLUDED_RISK".equals(st)) { r[2].fail("중대 위험 공시"); continue; }
                    else if ("PENDING".equals(st)) {
                        if (cov < 70) { r[2].nodata("커버리지 70% 미만"); continue; }
                        if ("REVIEW".equals(dis) || "BLOCK".equals(dis)) { r[2].fail("검토 필요 공시"); continue; }
                        r[2].nodata("공시 스캔 미확인"); continue;
                    } else { r[2].fail("OIS 65점 미만(" + st + ")"); continue; }
                    if (msi == null) { r[3].nodata("MSI 미산출"); continue; }
                    if (Math.round(msi) > thr[v]) { r[3].fail("MSI " + thr[v] + " 초과"); continue; }
                    r[3].pass++;
                    if (!(dd instanceof Number)) { r[4].nodata("60일 낙폭 자료 없음"); continue; }
                    if (((Number) dd).doubleValue() > -0.15) { r[4].fail("낙폭 -15% 미달"); continue; }
                    r[4].pass++;
                }
            }
            // 자금 한도: 해당 기준일 신호로 만든 1차 매수 주문의 체결 결과
            for (int v = 0; v < 2; v++) {
                Row r = rows.get(acct[v])[5];
                for (Map<String, Object> o : db.query("SELECT status, status_reason FROM paper_orders WHERE account=? AND signal_date=? AND side='BUY' AND stage=1", acct[v], d)) {
                    String s = (String) o.get("status"), why = String.valueOf(o.get("status_reason"));
                    if ("FILLED".equals(s)) r.pass++;
                    else if ("UNFILLED".equals(s) && why.contains("수량 0")) r.fail("현금·종목 한도(수량 0)");
                    else if ("UNFILLED".equals(s)) r.nodata("체결일 시가 미확인");
                    else r.why.merge("대기: 다음 거래일 시가 체결 전", 1, Integer::sum);
                }
                for (Map<String, Object> sgl : db.query("SELECT reason FROM paper_signals WHERE account=? AND signal_date=? AND decision='DEFER' AND stage=1", acct[v], d)) {
                    r.nodata("주문 보류(필수 데이터 부족)");
                }
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("[").append(market).append("] ").append(from).append(" ~ ").append(to).append(" · ").append(ds.size()).append("거래일 · 종목·일 합계\n");
        if (runs > 0) b.append(String.format("유니버스(일평균): 상장 %d → 보통주 %d → OIS 유니버스 %d (시총 상위) · 일봉 수집 실패 합계 %d건%n", listed / runs, common / runs, uni / runs, barsFail));
        if (pruned > 0) b.append("※ 오래된 '투자 보류' 상세 ").append(pruned).append("건은 정리돼 단계 집계에서 제외\n");
        for (int v = 0; v < 2; v++) {
            boolean has = db.count("SELECT COUNT(*) FROM paper_accounts WHERE account=?", acct[v]) > 0;
            b.append(v == 0 ? "\n■ 원본 계좌(1차 MSI ≤ 15) " : "\n■ 실험 계좌(1차 MSI ≤ 20) ").append(acct[v]).append(has ? "" : " — 계좌 미생성(자금 한도 단계 기록 없음)").append("\n");
            b.append(String.format("%-28s %6s %10s %10s  주요 사유%n", "단계", "통과", "조건미충족", "데이터부족"));
            Row[] r = rows.get(acct[v]);
            for (int i = 0; i < STAGES.length; i++) {
                List<String> top = new ArrayList<>();
                r[i].why.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(3).forEach(e -> top.add(e.getKey() + " " + e.getValue()));
                b.append(String.format("%-28s %6d %10d %10d  %s%n", STAGES[i], r[i].pass, r[i].fail, r[i].nodata, String.join(", ", top)));
            }
        }
        return b.toString();
    }

    static long n(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }
}
