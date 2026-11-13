package screener.util;

import screener.model.SourceStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 공급원별 호출 제한(최소 간격), 재시도, 디스크 캐시를 제공하는 HTTP 클라이언트.
 * 캐시 키는 비밀값을 제거한 URL로 만들며, 캐시 본문에는 응답만 저장한다(키 미포함).
 */
public final class Http {
    public static final class HttpError extends IOException {
        public final int status;
        public HttpError(int status, String msg) { super(msg); this.status = status; }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final Path cacheDir;
    private final String defaultUa;
    private final Map<String, Long> minIntervalMs = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCall = new ConcurrentHashMap<>();
    private final java.util.Set<String> noRetry429 = ConcurrentHashMap.newKeySet();
    private volatile boolean offline;

    public Http(Path cacheDir, String defaultUa) {
        this.cacheDir = cacheDir;
        this.defaultUa = defaultUa;
    }

    public void setOffline(boolean off) { this.offline = off; }

    /** 공급원(호스트) 단위 최소 호출 간격 설정. */
    public void rateLimit(String host, long ms) { minIntervalMs.put(host, ms); }

    /** 일일 한도형 공급원: 429를 받으면 재시도하지 않고 즉시 실패(호출 측에서 소스를 중단). */
    public void noRetryOn429(String host) { noRetry429.add(host); }

    public String getText(SourceStatus st, String url, Map<String, String> headers, Duration ttl, Charset cs) throws IOException {
        return new String(get(st, "GET", url, headers, null, ttl), cs);
    }

    public String getText(SourceStatus st, String url, Map<String, String> headers, Duration ttl) throws IOException {
        return getText(st, url, headers, ttl, StandardCharsets.UTF_8);
    }

    public String postJson(SourceStatus st, String url, Map<String, String> headers, String body, Duration ttl) throws IOException {
        return new String(get(st, "POST", url, headers, body, ttl), StandardCharsets.UTF_8);
    }

    public byte[] get(SourceStatus st, String method, String url, Map<String, String> headers, String body, Duration ttl) throws IOException {
        String safeKey = Log.redact(method + " " + url + (body == null ? "" : " " + body));
        Path cf = cacheFile(st.name, safeKey);
        if (ttl != null && !ttl.isZero() && Files.exists(cf)) {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(cf).toMillis();
            if (age <= ttl.toMillis() || offline) {
                st.ok(true);
                return Files.readAllBytes(cf);
            }
        }
        if (offline) {
            st.fail("오프라인 모드: 캐시 없음");
            throw new IOException("오프라인 모드에서 캐시 없음: " + Log.redact(url));
        }
        URI uri = URI.create(url);
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            throttle(uri.getHost());
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                        .header("User-Agent", defaultUa).header("Accept-Encoding", "identity");
                if (headers != null) headers.forEach(rb::setHeader);
                if ("POST".equals(method)) rb.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                else rb.GET();
                HttpResponse<byte[]> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
                int sc = r.statusCode();
                if (sc == 200) {
                    if (ttl != null && !ttl.isZero()) {
                        Files.createDirectories(cf.getParent());
                        Files.write(cf, r.body());
                    }
                    st.ok(false);
                    return r.body();
                }
                if (sc == 204) { st.ok(false); return new byte[0]; }
                last = new HttpError(sc, "[" + st.name + "] HTTP " + sc + " " + Log.redact(snippet(new String(r.body(), StandardCharsets.UTF_8))));
                if (sc == 429 && noRetry429.contains(uri.getHost())) break;
                if (sc == 429 || sc >= 500) {
                    sleep(1500L * attempt * attempt);
                    continue;
                }
                break; // 4xx: 재시도 의미 없음
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                last = new IOException("중단됨");
                break;
            } catch (IOException e) {
                last = e;
                sleep(1000L * attempt);
            }
        }
        String msg = last == null ? "알 수 없는 오류" : last.getMessage();
        st.fail(Log.redact(msg));
        Log.warn("[" + st.name + "] 요청 실패 " + Log.redact(url) + " : " + Log.redact(msg));
        throw last == null ? new IOException("요청 실패") : last;
    }

    /** 오류 본문 요약: HTML 오류 페이지는 &lt;title&gt;만, 그 외는 앞 160자. */
    static String snippet(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(body);
        String s = m.find() ? "(HTML) " + m.group(1) : body;
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    private void throttle(String host) {
        Long min = minIntervalMs.get(host);
        if (min == null) return;
        synchronized (this) {
            long now = System.currentTimeMillis();
            long prev = lastCall.getOrDefault(host, 0L);
            long wait = prev + min - now;
            if (wait > 0) sleep(wait);
            lastCall.put(host, System.currentTimeMillis());
        }
    }

    private Path cacheFile(String source, String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String h = HexFormat.of().formatHex(md.digest(key.getBytes(StandardCharsets.UTF_8)));
            return cacheDir.resolve(source.replaceAll("[^A-Za-z0-9_-]", "_")).resolve(h.substring(0, 2)).resolve(h + ".bin");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
