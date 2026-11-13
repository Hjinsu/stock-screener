package screener.source;

import screener.model.DisclosureCheck;
import screener.model.Fundamentals;
import screener.model.SourceStatus;
import screener.engine.RiskRules;
import screener.util.Http;
import screener.util.Json;
import screener.util.Num;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * OpenDART (https://opendart.fss.or.kr). 무료 API 키(crtfc_key) 필요. 이용 한도는 약관상 홈페이지 게시(이번 검증에서 수치 미확인).
 * 사용: 고유번호(corpCode.xml), 다중회사 주요계정(fnlttMultiAcnt, 최대 100개사/호출), 공시검색(list).
 */
public final class DartSource {
    public static final String NAME = "OpenDART";
    private static final String BASE = "https://opendart.fss.or.kr/api/";
    private static final DateTimeFormatter D = DateTimeFormatter.BASIC_ISO_DATE;
    private final Http http;
    private final String key;
    private final SourceStatus st;

    public DartSource(Http http, String key, SourceStatus.Registry reg) {
        this.http = http;
        this.key = key;
        this.st = reg.get(NAME);
        http.rateLimit("opendart.fss.or.kr", 200);
        if (key == null) { st.disabled = true; st.note = "DART_API_KEY 미설정"; }
    }

    public boolean enabled() { return key != null; }

    /** 종목코드(6자리) → DART 고유번호. */
    public Map<String, String> corpCodes() throws Exception {
        byte[] zip = http.get(st, "GET", BASE + "corpCode.xml?crtfc_key=" + key, null, null, Duration.ofDays(7));
        String xml = null;
        try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                if (e.getName().toLowerCase().endsWith(".xml")) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    zi.transferTo(bo);
                    xml = bo.toString(StandardCharsets.UTF_8);
                }
            }
        }
        if (xml == null) throw new IllegalStateException("corpCode.xml 압축 해제 실패");
        Map<String, String> m = new HashMap<>();
        Matcher mt = Pattern.compile("<corp_code>(\\d+)</corp_code>\\s*<corp_name>[^<]*</corp_name>\\s*(?:<corp_eng_name>[^<]*</corp_eng_name>\\s*)?<stock_code>\\s*(\\w{6})\\s*</stock_code>").matcher(xml);
        while (mt.find()) m.put(mt.group(2), mt.group(1));
        st.items = m.size();
        return m;
    }

    /** 다중회사 주요계정. 반환: stock_code → 원시 계정(CFS 우선). */
    public Map<String, Map<String, String[]>> multiAccounts(List<String> corpCodes, int year, String reprt) throws Exception {
        String url = BASE + "fnlttMultiAcnt.json?crtfc_key=" + key + "&corp_code=" + String.join(",", corpCodes) + "&bsns_year=" + year + "&reprt_code=" + reprt;
        Map<String, Object> o = Json.obj(Json.parse(http.getText(st, url, null, Duration.ofHours(20))));
        String status = Json.str(o, "status");
        Map<String, Map<String, String[]>> out = new HashMap<>();
        if ("013".equals(status)) return out; // 조회 데이터 없음
        if (!"000".equals(status)) throw new IllegalStateException("DART " + status + " " + Json.str(o, "message"));
        Map<String, Boolean> hasCfs = new HashMap<>();
        for (Object x : Json.arr(o.get("list"))) {
            Map<String, Object> r = Json.obj(x);
            if ("CFS".equals(Json.str(r, "fs_div"))) hasCfs.put(Json.str(r, "stock_code"), true);
        }
        for (Object x : Json.arr(o.get("list"))) {
            Map<String, Object> r = Json.obj(x);
            String sc = Json.str(r, "stock_code");
            String fs = Json.str(r, "fs_div");
            if (sc == null) continue;
            if (hasCfs.getOrDefault(sc, false) && !"CFS".equals(fs)) continue;
            String acc = Json.str(r, "account_nm").replace(" ", "");
            Map<String, String[]> m = out.computeIfAbsent(sc, k -> new HashMap<>());
            m.putIfAbsent(acc, new String[]{Json.str(r, "thstrm_amount"), Json.str(r, "frmtrm_amount"), Json.str(r, "thstrm_add_amount"),
                    Json.str(r, "frmtrm_add_amount"), Json.str(r, "thstrm_dt"), Json.str(r, "rcept_no"), fs});
        }
        return out;
    }

    static Double amt(String[] v, int i) { return v == null || v[i] == null ? null : Num.parse(v[i]); }

    static String[] pick(Map<String, String[]> m, String... names) {
        for (String n : names) if (m.containsKey(n)) return m.get(n);
        return null;
    }

    /** 원시 계정 → Fundamentals. annual: 직전 사업연도 사업보고서 계정(TTM 계산용, 없으면 null). */
    public static Fundamentals toFundamentals(Map<String, String[]> m, Map<String, String[]> annual, int year, String reprt) {
        Fundamentals f = new Fundamentals();
        f.source = "OpenDART 다중회사 주요계정";
        f.period = year + " " + reportName(reprt);
        String[] rev = pick(m, "매출액", "수익(매출액)", "영업수익", "매출");
        String[] op = pick(m, "영업이익", "영업이익(손실)");
        String[] ni = pick(m, "당기순이익(손실)", "당기순이익", "분기순이익(손실)", "반기순이익(손실)", "분기순이익", "반기순이익");
        f.revenue = amt(rev, 0); f.revenuePrev = amt(rev, 1);
        f.opIncome = amt(op, 0); f.opIncomePrev = amt(op, 1);
        f.netIncome = amt(ni, 0); f.netIncomePrev = amt(ni, 1);
        f.totalLiab = amt(pick(m, "부채총계"), 0);
        f.totalEquity = amt(pick(m, "자본총계"), 0);
        f.capitalStock = amt(pick(m, "자본금"), 0);
        f.currentAssets = amt(pick(m, "유동자산"), 0);
        f.currentLiab = amt(pick(m, "유동부채"), 0);
        String[] bs = pick(m, "자산총계", "부채총계");
        if (bs != null && bs[4] != null) {
            Matcher mt = Pattern.compile("(\\d{4})\\.(\\d{2})\\.(\\d{2})").matcher(bs[4]);
            if (mt.find()) f.periodEnd = mt.group(1) + "-" + mt.group(2) + "-" + mt.group(3);
        }
        if (bs != null) f.filedOrReceipt = bs[5];
        if ("11011".equals(reprt)) {
            f.annualNetIncome = f.netIncome;
            f.earningsNote = "연간 순이익(" + year + ")";
        } else if (annual != null && ni != null) {
            Double ytd = amt(ni, 2) != null ? amt(ni, 2) : amt(ni, 0);
            Double ytdPrev = amt(ni, 3) != null ? amt(ni, 3) : amt(ni, 1);
            Double an = amt(pick(annual, "당기순이익(손실)", "당기순이익"), 0);
            if (ytd != null && ytdPrev != null && an != null) {
                f.annualNetIncome = an + ytd - ytdPrev;
                f.earningsNote = "TTM 순이익(직전 연간+당해 누적−전년 누적)";
            } else if (an != null) {
                f.annualNetIncome = an;
                f.earningsNote = "직전 사업연도 순이익";
            }
        }
        return f;
    }

    public static String reportName(String r) {
        switch (r) { case "11013": return "1분기"; case "11012": return "반기"; case "11014": return "3분기"; case "11011": return "사업보고서"; default: return r; }
    }

    public DisclosureCheck disclosures(String corpCode, LocalDate asOf) {
        LocalDate from = asOf.minusDays(90);
        try {
            String url = BASE + "list.json?crtfc_key=" + key + "&corp_code=" + corpCode + "&bgn_de=" + from.format(D) + "&end_de=" + asOf.format(D) + "&page_count=100";
            Map<String, Object> o = Json.obj(Json.parse(http.getText(st, url, null, Duration.ofHours(12))));
            String status = Json.str(o, "status");
            DisclosureCheck c = new DisclosureCheck();
            c.ok = true;
            c.source = NAME + " 공시검색";
            c.window = from + "~" + asOf;
            if ("013".equals(status)) return c;
            if (!"000".equals(status)) return DisclosureCheck.failed(NAME, "status " + status + " " + Json.str(o, "message"));
            List<Object> l = Json.arr(o.get("list"));
            c.scanned = l == null ? 0 : l.size();
            if (l != null) for (Object x : l) {
                Map<String, Object> r = Json.obj(x);
                String title = Json.str(r, "report_nm");
                if (title == null) continue;
                DisclosureCheck.Level lv = RiskRules.classifyKr(title);
                if (lv == DisclosureCheck.Level.INFO) continue;
                String d = Json.str(r, "rcept_dt");
                c.events.add(new DisclosureCheck.Event(d == null ? "" : LocalDate.parse(d, D).toString(), title.trim(), lv, RiskRules.krRuleOf(title),
                        "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + Json.str(r, "rcept_no")));
            }
            return c;
        } catch (Exception e) {
            return DisclosureCheck.failed(NAME, e.getMessage());
        }
    }

    public static List<String[]> reportOrder(LocalDate asOf) {
        int y = asOf.getYear();
        List<String[]> l = new ArrayList<>();
        l.add(new String[]{String.valueOf(y), "11014"});
        l.add(new String[]{String.valueOf(y), "11012"});
        l.add(new String[]{String.valueOf(y), "11013"});
        l.add(new String[]{String.valueOf(y - 1), "11011"});
        l.add(new String[]{String.valueOf(y - 1), "11014"});
        return l;
    }
}
