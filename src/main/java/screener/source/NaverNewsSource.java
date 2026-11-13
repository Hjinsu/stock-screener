package screener.source;

import screener.model.SourceStatus;
import screener.util.Http;
import screener.util.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NAVER API HUB 뉴스 검색 (https://api.ncloud-docs.com/docs/naver-api-hub-search-news).
 * GET https://naverapihub.apigw.ntruss.com/search/v1/news, 헤더 X-NCP-APIGW-API-KEY-ID / X-NCP-APIGW-API-KEY.
 * display 1~100, start 1~1000, sort=date. 기존 developers.naver.com 키는 HUB에서 사용할 수 없다.
 * 한도: 문서상 검색 API 일 25,000회 / HUB FAQ 월 775,000건·키당 50 RPS(문서 간 표기 상이) — 한시적 무료.
 */
public final class NaverNewsSource {
    public static final String NAME = "NAVER API HUB 뉴스 검색";
    private static final String BASE = "https://naverapihub.apigw.ntruss.com/search/v1/news";
    private static final DateTimeFormatter RFC = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
    private final Http http;
    private final String id, secret;
    private final SourceStatus st;

    public record Item(String title, String description, String link, String originallink, ZonedDateTime pubDate) { }

    public NaverNewsSource(Http http, String id, String secret, SourceStatus.Registry reg) {
        this.http = http;
        this.id = id;
        this.secret = secret;
        this.st = reg.get(NAME);
        http.rateLimit("naverapihub.apigw.ntruss.com", 200);
        http.noRetryOn429("naverapihub.apigw.ntruss.com");
        if (!enabled()) { st.disabled = true; st.note = "NAVER_CLIENT_ID/SECRET(API HUB) 미설정 — 뉴스 심리 미평가"; }
    }

    public boolean enabled() { return id != null && secret != null; }

    /** 최신순 검색 한 페이지. */
    public List<Item> page(String query, int start, int display) throws Exception {
        String url = BASE + "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&display=" + display + "&start=" + start + "&sort=date&format=json";
        String body = http.getText(st, url, Map.of("X-NCP-APIGW-API-KEY-ID", id, "X-NCP-APIGW-API-KEY", secret), Duration.ofMinutes(30));
        Map<String, Object> o = Json.obj(Json.parse(body));
        List<Item> out = new ArrayList<>();
        List<Object> items = o == null ? null : Json.arr(o.get("items"));
        if (items == null) return out;
        for (Object x : items) {
            Map<String, Object> m = Json.obj(x);
            ZonedDateTime pd;
            try { pd = ZonedDateTime.parse(Json.str(m, "pubDate"), RFC); } catch (Exception e) { continue; }
            out.add(new Item(Json.str(m, "title"), Json.str(m, "description"), Json.str(m, "link"), Json.str(m, "originallink"), pd));
        }
        st.items += out.size();
        return out;
    }
}
