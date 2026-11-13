package screener.report;

import screener.Config;
import screener.store.Db;
import screener.util.Json;
import screener.util.Log;
import screener.util.Num;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 카카오톡 '나와의 채팅' 모의매매 보고(공식 REST API: 나에게 기본 템플릿으로 메시지 발송).
 * 사용자가 켜는 옵션. 토큰은 DB가 아닌 SCREENER_HOME/secure/kakao-token.json(소유자 전용)에 저장해 백업·CSV에 섞이지 않게 한다.
 * 발송 기록은 kakao_reports(계좌·시장·거래일·보고 유형 UNIQUE)로 중복을 막는다. 응답 유실(전송 후 예외)은 UNKNOWN 으로 남기고 자동 재발송하지 않는다.
 * 키·토큰은 메시지·로그·CSV에 넣지 않는다.
 */
public final class KakaoReporter {
    public static final String AUTH = "https://kauth.kakao.com";
    public static final String API = "https://kapi.kakao.com";
    public static final int TEXT_MAX = 200; // 기본 텍스트 템플릿 text 최대 길이

    /** HTTP 전송 추상화(테스트에서 모의 응답 주입). 전송 후 응답 유실은 IOException 으로 표현. */
    public interface Transport { Response post(String url, Map<String, String> headers, Map<String, String> form) throws IOException; }

    public record Response(int status, String body) { }

    private final Config cfg;
    private final Db db;
    private final Transport tx;
    private final Path tokenFile;

    public KakaoReporter(Config cfg, Db db) { this(cfg, db, defaultTransport()); }

    public KakaoReporter(Config cfg, Db db, Transport tx) {
        this.cfg = cfg;
        this.db = db;
        this.tx = tx;
        this.tokenFile = cfg.home.resolve("secure").resolve("kakao-token.json");
    }

    public boolean configured() { return cfg.kakaoRestKey != null; }

    public boolean connected() { return Files.exists(tokenFile); }

    // ---------------- OAuth ----------------
    public String authorizeUrl(String state) {
        return AUTH + "/oauth/authorize?response_type=code&scope=talk_message&client_id=" + enc(cfg.kakaoRestKey)
                + "&redirect_uri=" + enc(cfg.kakaoRedirectUri) + "&state=" + enc(state);
    }

    /** 인가 코드 → 토큰 저장. */
    public void exchangeCode(String code) throws IOException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "authorization_code");
        f.put("client_id", cfg.kakaoRestKey);
        f.put("redirect_uri", cfg.kakaoRedirectUri);
        f.put("code", code);
        if (cfg.kakaoClientSecret != null) f.put("client_secret", cfg.kakaoClientSecret);
        Response r = tx.post(AUTH + "/oauth/token", Map.of(), f);
        if (r.status() != 200) throw new IOException("토큰 발급 실패 HTTP " + r.status() + " " + errorCode(r.body()));
        saveToken(Json.obj(Json.parse(r.body())), null);
    }

    /** 유효한 액세스 토큰(만료 5분 전이면 갱신). */
    String accessToken() throws IOException {
        Map<String, Object> t = loadToken();
        if (t == null) throw new IOException("카카오 미연결");
        long exp = ((Number) t.get("expires_at")).longValue();
        if (Instant.now().getEpochSecond() < exp - 300) return (String) t.get("access_token");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("client_id", cfg.kakaoRestKey);
        f.put("refresh_token", (String) t.get("refresh_token"));
        if (cfg.kakaoClientSecret != null) f.put("client_secret", cfg.kakaoClientSecret);
        Response r;
        try { r = tx.post(AUTH + "/oauth/token", Map.of(), f); }
        catch (IOException e) { throw new IOException("토큰 갱신 실패(네트워크): " + e.getMessage()); }
        if (r.status() != 200) throw new IOException("토큰 갱신 실패 HTTP " + r.status() + " " + errorCode(r.body()) + " — 대시보드에서 다시 연결 필요");
        return saveToken(Json.obj(Json.parse(r.body())), (String) t.get("refresh_token"));
    }

    private String saveToken(Map<String, Object> o, String oldRefresh) throws IOException {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("access_token", o.get("access_token"));
        Object rt = o.get("refresh_token");
        t.put("refresh_token", rt != null ? rt : oldRefresh); // 갱신 응답은 refresh_token 을 생략할 수 있음
        long ttl = o.get("expires_in") instanceof Number ? ((Number) o.get("expires_in")).longValue() : 3600;
        t.put("expires_at", Instant.now().getEpochSecond() + ttl);
        t.put("saved_at", LocalDateTime.now().withNano(0).toString());
        Files.createDirectories(tokenFile.getParent());
        Path tmp = tokenFile.resolveSibling("kakao-token.json.tmp");
        Files.writeString(tmp, Json.write(t), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(tokenFile.getParent(), PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) { /* Windows: 사용자 프로필 폴더 권한에 의존 */ }
        Files.move(tmp, tokenFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Log.registerSecret((String) t.get("access_token"));
        Log.registerSecret((String) t.get("refresh_token"));
        return (String) t.get("access_token");
    }

    private Map<String, Object> loadToken() throws IOException {
        if (!Files.exists(tokenFile)) return null;
        Map<String, Object> t = Json.obj(Json.parse(Files.readString(tokenFile, StandardCharsets.UTF_8)));
        if (t != null) { Log.registerSecret((String) t.get("access_token")); Log.registerSecret((String) t.get("refresh_token")); }
        return t;
    }

    public Map<String, Object> tokenStatus() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", configured());
        m.put("connected", connected());
        Map<String, Object> t = loadToken();
        if (t != null) {
            m.put("savedAt", t.get("saved_at"));
            m.put("accessExpiresAt", LocalDateTime.ofEpochSecond(((Number) t.get("expires_at")).longValue(), 0, java.time.ZoneOffset.ofHours(9)).toString() + " KST");
        }
        return m;
    }

    /** 연결 해제: 카카오 연결 끊기(unlink) 후 토큰 파일 삭제. 해제 요청이 실패해도 로컬 토큰은 삭제. */
    public String disconnect() throws IOException {
        String msg = "로컬 토큰 삭제";
        try {
            Response r = tx.post(API + "/v1/user/unlink", Map.of("Authorization", "Bearer " + accessToken()), Map.of());
            msg = r.status() == 200 ? "카카오 연결 끊기 완료, 로컬 토큰 삭제" : "카카오 연결 끊기 HTTP " + r.status() + " — 로컬 토큰만 삭제(카카오 계정 설정에서 연결 해제 확인)";
        } catch (IOException e) {
            msg = "카카오 연결 끊기 요청 실패(" + Log.redact(e.getMessage()) + ") — 로컬 토큰만 삭제";
        }
        Files.deleteIfExists(tokenFile);
        return msg;
    }

    // ---------------- 발송 ----------------
    /** 실제 발송 1회(기록 없음 — 테스트 메시지용). */
    public Response sendText(String text) throws IOException {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("web_url", cfg.kakaoLinkUrl);
        link.put("mobile_web_url", cfg.kakaoLinkUrl);
        Map<String, Object> tpl = new LinkedHashMap<>();
        tpl.put("object_type", "text");
        tpl.put("text", fit(text));
        tpl.put("link", link);
        return tx.post(API + "/v2/api/talk/memo/default/send", Map.of("Authorization", "Bearer " + accessToken()), Map.of("template_object", Json.write(tpl)));
    }

    public static String fit(String s) {
        if (s.length() <= TEXT_MAX) return s;
        return s.substring(0, TEXT_MAX - 13) + "…(대시보드 참조)";
    }

    /** 기존 호출 호환: 요약문을 DB 모의매매 결과로 생성. */
    public Map<String, Object> report(String market, String tradeDate, boolean confirmed, String unconfirmedReason) {
        return daily(market, tradeDate, confirmed, unconfirmedReason, null, java.time.LocalDateTime.now().withNano(0).toString());
    }

    /**
     * 일일 요약 1통. 확정이면 DAILY, 아니면 DAILY_UNCONFIRMED. checkedAt = 신호·데이터를 실제로 확인한 시각(기록용).
     */
    public Map<String, Object> daily(String market, String tradeDate, boolean confirmed, String why, String summary, String checkedAt) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (!enabled(market)) { out.put("status", "DISABLED"); return out; }
            String account = market + "-" + ("KR".equals(market) ? "KRW" : "USD");
            String type = confirmed ? "DAILY" : "DAILY_UNCONFIRMED";
            String text = confirmed ? (summary != null ? summary : buildDaily(account, market, tradeDate))
                    : "[모의매매·미확정] " + market + " " + tradeDate + "\n데이터 미확정으로 정상 보고를 생략합니다.\n사유: " + why;
            out.putAll(dispatch("kakao_reports", new String[]{"account", "market", "trade_date", "report_type"}, new String[]{account, market, tradeDate, type}, fit(text), checkedAt));
            out.put("type", type);
        } catch (Exception e) {
            Log.warn("카카오 보고 처리 실패(격리): " + Log.redact(String.valueOf(e.getMessage())));
            out.put("status", "ERROR");
            out.put("error", Log.redact(String.valueOf(e.getMessage())));
        }
        return out;
    }

    /** 상태 변화 알림(같은 거래일·유형·대상은 1회). */
    public Map<String, Object> event(String market, String tradeDate, String type, String subject, String text, String checkedAt) {
        try {
            if (!enabled(market)) return Map.of("status", "DISABLED");
            return dispatch("kakao_events", new String[]{"market", "trade_date", "event_type", "subject"}, new String[]{market, tradeDate, type, subject}, fit(text), checkedAt);
        } catch (Exception e) {
            Log.warn("카카오 알림 처리 실패(격리): " + Log.redact(String.valueOf(e.getMessage())));
            return Map.of("status", "ERROR");
        }
    }

    /** 주간 보고 1통(한·미 중 하나라도 보고가 켜져 있으면). */
    public Map<String, Object> weekly(String weekEnd, String text, String checkedAt) {
        try {
            if (!configured() || !(enabledFlag("KR") || enabledFlag("US"))) return Map.of("status", "DISABLED");
            if (!connected()) return Map.of("status", "NOT_CONNECTED");
            return dispatch("kakao_reports", new String[]{"account", "market", "trade_date", "report_type"}, new String[]{"ALL", "ALL", weekEnd, "WEEKLY"}, fit(text), checkedAt);
        } catch (Exception e) {
            Log.warn("카카오 주간 보고 실패(격리): " + Log.redact(String.valueOf(e.getMessage())));
            return Map.of("status", "ERROR");
        }
    }

    private boolean enabledFlag(String market) throws Exception { return "true".equals(db.setting("kakao_enabled_" + market, "false")); }

    private boolean enabled(String market) throws Exception { return configured() && enabledFlag(market); }

    /**
     * 공통 발송: 자연키로 1회만. 기존 행이 SENT·UNKNOWN·PENDING 이면 건너뛰고 FAILED 만 재시도.
     * 전송 전 실패(토큰) → FAILED, 전송 중 응답 유실 → UNKNOWN(자동 재발송 금지).
     */
    private Map<String, Object> dispatch(String table, String[] keyCols, String[] keyVals, String text, String checkedAt) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!connected()) { out.put("status", "NOT_CONNECTED"); return out; }
        String where = String.join(" AND ", java.util.Arrays.stream(keyCols).map(k -> k + "=?").toList());
        List<Map<String, Object>> ex = db.query("SELECT id, status FROM " + table + " WHERE " + where, (Object[]) keyVals);
        if (!ex.isEmpty() && !"FAILED".equals(ex.get(0).get("status"))) { out.put("status", "ALREADY_" + ex.get(0).get("status")); return out; }
        long id;
        if (ex.isEmpty()) {
            String cols = String.join(", ", keyCols) + ", status, message_text, attempts, created_at, checked_at";
            String ph = String.join(",", java.util.Collections.nCopies(keyCols.length, "?")) + ",'PENDING',?,1,?,?";
            try (var ps = db.prepare("INSERT INTO " + table + "(" + cols + ") VALUES(" + ph + ")", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                int i = 1;
                for (String v : keyVals) ps.setString(i++, v);
                ps.setString(i++, text); ps.setString(i++, now()); ps.setString(i, checkedAt);
                ps.executeUpdate();
                try (var rs = ps.getGeneratedKeys()) { rs.next(); id = rs.getLong(1); }
            }
        } else {
            id = ((Number) ex.get(0).get("id")).longValue();
            update(table, id, "PENDING", null, null, text, true);
        }
        try {
            Response r = sendText(text);
            boolean ok = r.status() == 200 && r.body() != null && r.body().contains("\"result_code\":0");
            update(table, id, ok ? "SENT" : "FAILED", r.status(), ok ? null : "HTTP " + r.status() + " " + errorCode(r.body()), null, false);
            out.put("status", ok ? "SENT" : "FAILED");
        } catch (IOException e) {
            boolean beforeSend = e.getMessage() != null && (e.getMessage().startsWith("토큰") || e.getMessage().startsWith("카카오 미연결"));
            update(table, id, beforeSend ? "FAILED" : "UNKNOWN", null, Log.redact(e.getMessage()), null, false);
            out.put("status", beforeSend ? "FAILED" : "UNKNOWN");
        }
        return out;
    }

    private void update(String table, long id, String status, Integer http, String error, String text, boolean incAttempt) throws Exception {
        try (var ps = db.prepare("UPDATE " + table + " SET status=?, http_status=?, error=?, sent_at=?, message_text=COALESCE(?, message_text)"
                + (incAttempt ? ", attempts=attempts+1" : "") + " WHERE id=?")) {
            ps.setString(1, status);
            if (http == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setInt(2, http);
            ps.setString(3, error);
            ps.setString(4, "SENT".equals(status) ? now() : null);
            ps.setString(5, text);
            ps.setLong(6, id);
            ps.executeUpdate();
        }
    }

    /** DB에 저장된 모의매매 결과로 일일 보고 생성(한국어, 200자 이내). */
    String buildDaily(String account, String market, String d) throws Exception {
        List<Map<String, Object>> eq = db.query("SELECT date, equity, deposits_cum, equity_krw FROM paper_equity_daily WHERE account=? AND date<=? ORDER BY date DESC LIMIT 2", account, d);
        List<Map<String, Object>> fills = db.query("SELECT side, name, stage FROM paper_fills WHERE account=? AND fill_date=? ORDER BY id", account, d);
        List<Map<String, Object>> unf = db.query("SELECT name, status_reason FROM paper_orders WHERE account=? AND status='UNFILLED' AND target_date=? ORDER BY id", account, d);
        int deferred = db.count("SELECT COUNT(*) FROM paper_signals WHERE account=? AND decision='DEFER' AND signal_date=?", account, d);
        long buys = fills.stream().filter(f -> "BUY".equals(f.get("side"))).count(), sells = fills.size() - buys;
        StringBuilder b = new StringBuilder("[모의매매] " + market + " " + d + "\n");
        b.append("체결 ").append(fills.size()).append("건(매수 ").append(buys).append("·매도 ").append(sells).append(")");
        if (!fills.isEmpty()) b.append(": ").append(fills.stream().limit(3).map(f -> f.get("name") + ("BUY".equals(f.get("side")) ? " 매수" : " 매도")).collect(Collectors.joining(", ")));
        b.append("\n");
        if (!unf.isEmpty()) b.append("미체결 ").append(unf.size()).append("건: ").append(short40(String.valueOf(unf.get(0).get("status_reason")))).append("\n");
        if (deferred > 0) b.append("보류 ").append(deferred).append("건(데이터 부족)\n");
        if (!eq.isEmpty()) {
            double e0 = ((Number) eq.get(0).get("equity")).doubleValue(), dep = ((Number) eq.get(0).get("deposits_cum")).doubleValue();
            Double prev = eq.size() > 1 ? ((Number) eq.get(1).get("equity")).doubleValue() : null;
            b.append("일 손익 ").append(prev == null ? "-" : Num.money(market, e0 - prev)).append(" / 누적 ").append(Num.pct(e0 / dep - 1)).append("\n");
            b.append("총자산 ").append(Num.money(market, e0));
            if ("US".equals(market) && eq.get(0).get("equity_krw") != null) b.append(" (").append(Num.krw(((Number) eq.get(0).get("equity_krw")).doubleValue())).append(")");
        }
        return b.toString();
    }

    private static String short40(String s) { return s.length() > 40 ? s.substring(0, 40) + "…" : s; }

    static String errorCode(String body) {
        if (body == null) return "";
        try { Map<String, Object> o = Json.obj(Json.parse(body)); Object c = o.get("code") != null ? o.get("code") : o.get("error_code"); Object m = o.get("msg") != null ? o.get("msg") : o.get("error"); return "code=" + c + " " + m; }
        catch (Exception e) { return ""; }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String now() { return LocalDateTime.now().withNano(0).toString(); }

    static Transport defaultTransport() {
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (url, headers, form) -> {
            String body = form.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(rb::header);
            try {
                HttpResponse<String> r = c.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new Response(r.statusCode(), r.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("중단됨");
            }
        };
    }
}
