package screener.engine;

import screener.source.NaverNewsSource;
import screener.store.Db;
import screener.util.Json;
import screener.util.Log;

import java.net.URI;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 뉴스 심리(MSI '뉴스·커뮤니티' 영역 중 뉴스). 원본 02 §4·skill contrarian.md 규칙:
 * 7단계(극단 공포/패닉셀/비관/중립/낙관/과도한 낙관/극단 환희 → 0/15/30/50/70/85/100), 광고·중복(기사 복제) 제거,
 * 작성자(언론사 도메인)별 평균을 먼저 낸 뒤 평균, 독립 출처 20곳 미만이면 미평가.
 * 분류는 규칙 기반 키워드 초기 버전(NEWS v1.0)으로 풍자·반어는 판별하지 못한다. 커뮤니티 게시물은 수집하지 않는다.
 * 기준일 창: 직전 거래일 장 마감 ~ 기준일 장 마감(현지). 한 번 확정된 기준일 결과는 재수집하지 않는다(시점 보존).
 */
public final class NewsSentiment {
    public static final String VERSION = "NEWS v1.0 (규칙 기반 키워드, 언론사 평균, 최소 독립 출처 20)";
    public static final int MIN_OUTLETS = 20;
    static final int[] LEVEL_SCORE = {0, 15, 30, 50, 70, 85, 100};
    public static final String[] LEVEL_NAME = {"극단적 공포", "패닉셀", "비관", "중립", "낙관", "과도한 낙관", "극단적 환희"};

    static final String[] EXT_FEAR = {"폭락", "대폭락", "패닉", "공포", "블랙먼데이", "검은 월요일", "서킷브레이커", "사이드카", "붕괴", "쇼크", "대참사", "아비규환"};
    static final String[] PANIC_SELL = {"투매", "패닉셀", "손절", "반대매매", "엑소더스", "탈출 러시", "투매 행렬"};
    static final String[] NEG = {"하락", "약세", "급락", "우려", "매도세", "순매도", "경계", "부진", "하락세", "위축", "불안", "침체", "둔화", "악재", "내림세", "후퇴", "털썩"};
    static final String[] POS = {"상승", "강세", "반등", "회복", "매수세", "순매수", "호조", "개선", "오름세", "상승세", "호재", "훈풍"};
    static final String[] GREED = {"사상 최고", "사상최고", "최고치", "신고가", "폭등", "랠리", "과열"};
    static final String[] EUPHORIA = {"광풍", "불장", "포모", "FOMO", "묻지마", "열광", "광기"};
    static final Pattern AD = Pattern.compile("\\[광고\\]|\\[AD\\]|리딩방|무료 ?추천|종목 ?추천|수익 ?보장|카톡|텔레그램|이벤트|급등주 ?(무료|추천)|VIP ?회원");
    static final Pattern TAG = Pattern.compile("<[^>]+>");

    public static Map<String, String[]> QUERIES = Map.of(
            "KR", new String[]{"코스피", "코스닥", "국내 증시"},
            "US", new String[]{"뉴욕증시", "나스닥", "S&P500", "미국 증시"});

    private NewsSentiment() { }

    public static String clean(String s) {
        if (s == null) return "";
        return TAG.matcher(s).replaceAll("").replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").trim();
    }

    static int count(String t, String[] words) {
        int n = 0;
        for (String w : words) { int i = 0; while ((i = t.indexOf(w, i)) >= 0) { n++; i += w.length(); } }
        return n;
    }

    /** 0~6 단계. */
    public static int classify(String text) {
        // '매수 사이드카'는 급등 시 발동(강세) — 공포 키워드 '사이드카'와 구분. 등락 화살표는 방향어로 계산
        String t = clean(text).replace("매수 사이드카", "강세 ").replace("매수사이드카", "강세 ")
                .replace("↑", " 상승 ").replace("▲", " 상승 ").replace("↓", " 하락 ").replace("▼", " 하락 ");
        int e = count(t, EXT_FEAR), ps = count(t, PANIC_SELL), n = count(t, NEG), p = count(t, POS), g = count(t, GREED), u = count(t, EUPHORIA);
        int neg = n + 2 * e + 2 * ps, pos = p + 2 * g + 2 * u;
        if (neg - pos >= 1) return e >= 1 ? 0 : ps >= 1 ? 1 : 2;
        if (pos - neg >= 1) return u >= 1 ? 6 : g >= 1 ? 5 : 4;
        return 3;
    }

    public static boolean isAd(String title) { return AD.matcher(clean(title)).find(); }

    /** 중복(기사 복제) 판정 키: 괄호 머리말·기호·공백 제거. */
    public static String dupKey(String title) {
        return clean(title).replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)", "").replaceAll("[^0-9A-Za-z가-힣]", "").toLowerCase();
    }

    public static String outlet(NaverNewsSource.Item it) {
        String u = it.originallink() != null && !it.originallink().isBlank() ? it.originallink() : it.link();
        try {
            String h = URI.create(u.trim()).getHost();
            if (h == null) return "unknown";
            h = h.toLowerCase();
            return h.startsWith("www.") ? h.substring(4) : h.startsWith("m.") ? h.substring(2) : h;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 수집 항목에서 일일 심리 산출(순수 함수 — 테스트 가능). */
    public static Map<String, Object> aggregate(List<NaverNewsSource.Item> items, ZonedDateTime from, ZonedDateTime to) {
        Set<String> seenLink = new HashSet<>(), seenTitle = new HashSet<>();
        int raw = 0, outside = 0, dup = 0, ad = 0;
        int[] lv = new int[7];
        Map<String, List<Integer>> byOutlet = new LinkedHashMap<>();
        List<Map<String, Object>> kept = new ArrayList<>();
        for (NaverNewsSource.Item it : items) {
            raw++;
            if (!it.pubDate().isAfter(from) || it.pubDate().isAfter(to)) { outside++; continue; }
            String link = it.originallink() != null && !it.originallink().isBlank() ? it.originallink() : it.link();
            String key = dupKey(it.title());
            if (!seenLink.add(link) || key.length() < 6 || !seenTitle.add(key)) { dup++; continue; }
            if (isAd(it.title())) { ad++; continue; }
            int l = classify(it.title() + " " + it.description());
            lv[l]++;
            String o = outlet(it);
            byOutlet.computeIfAbsent(o, k -> new ArrayList<>()).add(LEVEL_SCORE[l]);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("outlet", o); r.put("title", clean(it.title())); r.put("link", link); r.put("pub", it.pubDate().toString()); r.put("level", l);
            kept.add(r);
        }
        double sum = 0;
        for (List<Integer> v : byOutlet.values()) sum += v.stream().mapToInt(Integer::intValue).average().orElse(50);
        Map<String, Object> m = new LinkedHashMap<>();
        int n = kept.size();
        m.put("raw", raw); m.put("outsideWindow", outside); m.put("duplicates", dup); m.put("ads", ad); m.put("kept", n);
        m.put("outlets", byOutlet.size());
        Map<String, Integer> lc = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) lc.put(LEVEL_NAME[i], lv[i]);
        m.put("levels", lc);
        m.put("intensity", n == 0 ? null : (double) (lv[0] + lv[1] + lv[5] + lv[6]) / n);
        Double score = byOutlet.isEmpty() ? null : sum / byOutlet.size();
        m.put("rawScore", score);
        boolean ok = byOutlet.size() >= MIN_OUTLETS;
        m.put("score", ok ? score : null);
        m.put("status", ok ? "OK" : "INSUFFICIENT");
        m.put("note", ok ? "언론사별 평균 후 평균(" + byOutlet.size() + "곳)" : "독립 출처 " + byOutlet.size() + "곳 < " + MIN_OUTLETS + " — 미평가");
        m.put("items", kept);
        return m;
    }

    /** 기준일 창 계산: 직전 거래일 마감 ~ 기준일 마감(현지). */
    public static ZonedDateTime[] window(String market, LocalDate prev, LocalDate asOf) {
        ZoneId z = ZoneId.of("KR".equals(market) ? "Asia/Seoul" : "America/New_York");
        LocalTime close = "KR".equals(market) ? LocalTime.of(15, 30) : LocalTime.of(16, 0);
        return new ZonedDateTime[]{ZonedDateTime.of(prev, close, z), ZonedDateTime.of(asOf, close, z)};
    }

    /**
     * 기준일 뉴스 심리를 확보(없으면 수집해 저장)하고, 저장된 일별 점수 시계열을 반환. 수집 실패는 격리(빈 결과).
     */
    public static Map<LocalDate, Double> ensure(Db db, NaverNewsSource src, String market, LocalDate prev, LocalDate asOf, List<String> notes) {
        Map<LocalDate, Double> series = new HashMap<>();
        try {
            if (src != null && src.enabled() && prev != null && db.count("SELECT COUNT(*) FROM news_daily WHERE market=? AND date=? AND status='OK'", market, asOf.toString()) == 0) {
                ZonedDateTime[] w = window(market, prev, asOf);
                List<NaverNewsSource.Item> all = new ArrayList<>();
                int calls = 0, full = 0;
                List<String> partial = new ArrayList<>();
                double windowHours = java.time.Duration.between(w[0], w[1]).toMinutes() / 60.0;
                for (String q : QUERIES.get(market)) {
                    boolean qReached = false;
                    ZonedDateTime earliest = null;
                    for (int start = 1; start <= 901; start += 100) {
                        List<NaverNewsSource.Item> pg = src.page(q, start, 100);
                        calls++;
                        all.addAll(pg);
                        for (NaverNewsSource.Item x : pg) if (earliest == null || x.pubDate().isBefore(earliest)) earliest = x.pubDate();
                        if (pg.isEmpty() || pg.stream().anyMatch(x -> !x.pubDate().isAfter(w[0]))) { qReached = true; break; }
                    }
                    if (qReached) full++;
                    else {
                        double cov = earliest == null || !earliest.isBefore(w[1]) ? 0 : java.time.Duration.between(earliest, w[1]).toMinutes() / 60.0;
                        partial.add("'" + q + "' 창 후반 " + String.format("%.1f", Math.min(cov, windowHours)) + "/" + String.format("%.1f", windowHours) + "시간만 표본(최신순 1,000건 상한)");
                    }
                }
                Map<String, Object> agg = aggregate(all, w[0], w[1]);
                String status = (String) agg.get("status");
                String note = (String) agg.get("note");
                if (full == 0) { status = "PARTIAL"; note = "모든 질의가 검색 상한(1,000건)으로 창 시작(" + w[0] + ")까지 도달하지 못함 — 미평가; " + note; }
                else if (!partial.isEmpty()) note = note + "; 창 전체 수집 질의 " + full + "개, 일부 표본: " + String.join(", ", partial);
                save(db, market, asOf, w, agg, status, note, calls);
            }
            for (Map<String, Object> r : db.query("SELECT date, score FROM news_daily WHERE market=? AND status='OK' AND score IS NOT NULL", market))
                series.put(LocalDate.parse((String) r.get("date")), ((Number) r.get("score")).doubleValue());
            List<Map<String, Object>> cur = db.query("SELECT status, note, outlets, n_kept FROM news_daily WHERE market=? AND date=?", market, asOf.toString());
            if (cur.isEmpty()) notes.add("뉴스 심리: " + (src == null || !src.enabled() ? "NAVER API HUB 키 미설정" : "기준일 수집 기록 없음") + " — 미평가");
            else if (!"OK".equals(cur.get(0).get("status"))) notes.add("뉴스 심리 미평가: " + cur.get(0).get("note"));
        } catch (Exception e) {
            notes.add("뉴스 심리 수집 실패(격리): " + Log.redact(String.valueOf(e.getMessage())));
            Log.warn("뉴스 심리 실패: " + Log.redact(String.valueOf(e.getMessage())));
        }
        return series;
    }

    @SuppressWarnings("unchecked")
    static void save(Db db, String market, LocalDate asOf, ZonedDateTime[] w, Map<String, Object> agg, String status, String note, int calls) throws Exception {
        List<Map<String, Object>> prevRow = db.query("SELECT n_kept AS kept FROM news_daily WHERE market=? AND date<? ORDER BY date DESC LIMIT 1", market, asOf.toString());
        Double growth = prevRow.isEmpty() || ((Number) prevRow.get(0).get("kept")).intValue() == 0 ? null
                : ((Number) agg.get("kept")).doubleValue() / ((Number) prevRow.get(0).get("kept")).doubleValue() - 1;
        synchronized (db) {
            try (PreparedStatement ps = db.prepare("INSERT OR REPLACE INTO news_daily(market, date, score, raw_score, status, note, n_raw, n_kept, n_dup, n_ads, n_outside," +
                    " outlets, levels_json, intensity, growth, window_from, window_to, queries, calls, fetched_at, rule_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int i = 1;
                ps.setString(i++, market); ps.setString(i++, asOf.toString());
                setD(ps, i++, "OK".equals(status) ? (Double) agg.get("score") : null); setD(ps, i++, (Double) agg.get("rawScore"));
                ps.setString(i++, status); ps.setString(i++, note);
                ps.setInt(i++, (Integer) agg.get("raw")); ps.setInt(i++, (Integer) agg.get("kept")); ps.setInt(i++, (Integer) agg.get("duplicates"));
                ps.setInt(i++, (Integer) agg.get("ads")); ps.setInt(i++, (Integer) agg.get("outsideWindow")); ps.setInt(i++, (Integer) agg.get("outlets"));
                ps.setString(i++, Json.write(agg.get("levels"))); setD(ps, i++, (Double) agg.get("intensity")); setD(ps, i++, growth);
                ps.setString(i++, w[0].toString()); ps.setString(i++, w[1].toString()); ps.setString(i++, String.join(", ", QUERIES.get(market)));
                ps.setInt(i++, calls); ps.setString(i++, LocalDateTime.now().withNano(0).toString()); ps.setString(i++, VERSION);
                ps.executeUpdate();
            }
            try (PreparedStatement del = db.prepare("DELETE FROM news_items WHERE market=? AND date=?")) {
                del.setString(1, market); del.setString(2, asOf.toString()); del.executeUpdate();
            }
            try (PreparedStatement ps = db.prepare("INSERT OR IGNORE INTO news_items(market, date, outlet, title, link, pub_ts, level) VALUES(?,?,?,?,?,?,?)")) {
                for (Map<String, Object> r : (List<Map<String, Object>>) agg.get("items")) {
                    ps.setString(1, market); ps.setString(2, asOf.toString()); ps.setString(3, (String) r.get("outlet")); ps.setString(4, (String) r.get("title"));
                    ps.setString(5, (String) r.get("link")); ps.setString(6, (String) r.get("pub")); ps.setInt(7, (Integer) r.get("level"));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    private static void setD(PreparedStatement ps, int i, Double v) throws java.sql.SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.REAL); else ps.setDouble(i, v);
    }
}
