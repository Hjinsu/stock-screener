package screener.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import screener.Config;
import screener.Runner;
import screener.engine.Rules;
import screener.store.Db;
import screener.util.Json;
import screener.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** 로컬 대시보드(127.0.0.1 바인딩). POST 요청은 사용자 지정 헤더(X-Requested-With)를 요구해 교차 사이트 요청을 막는다. */
public final class DashboardServer {
    private final Config cfg;
    private final Db db;
    private final int port;
    private HttpServer server;
    private volatile String lastManualRun = "";
    private volatile String kakaoState;
    private volatile long kakaoStateAt;

    public DashboardServer(Config cfg, Db db, int port) {
        this.cfg = cfg;
        this.db = db;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", this::handle);
        server.start();
    }

    public void stop() { if (server != null) server.stop(0); }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            if ("POST".equals(ex.getRequestMethod()) && !"screener".equals(ex.getRequestHeaders().getFirst("X-Requested-With"))) {
                send(ex, 403, "text/plain", "forbidden");
                return;
            }
            switch (path) {
                case "/": case "/index.html": {
                    try (InputStream in = getClass().getResourceAsStream("/web/dashboard.html")) {
                        send(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    return;
                }
                case "/kakao/login": {
                    screener.report.KakaoReporter k = new screener.report.KakaoReporter(cfg, db);
                    if (!k.configured()) { send(ex, 200, "text/html; charset=utf-8", page("KAKAO_REST_API_KEY 가 설정되지 않았습니다. 서버 환경 파일에 입력 후 대시보드를 재시작하세요.")); return; }
                    kakaoState = java.util.UUID.randomUUID().toString();
                    kakaoStateAt = System.currentTimeMillis();
                    ex.getResponseHeaders().set("Location", k.authorizeUrl(kakaoState));
                    send(ex, 302, "text/plain", "redirect");
                    return;
                }
                case "/kakao/callback": {
                    String st = q.get("state"), code = q.get("code");
                    String msg;
                    if (q.containsKey("error")) msg = "카카오 연결 취소/실패: " + q.get("error");
                    else if (st == null || !st.equals(kakaoState) || System.currentTimeMillis() - kakaoStateAt > 600_000) msg = "요청 검증(state) 실패 — 대시보드에서 다시 연결하세요.";
                    else {
                        kakaoState = null;
                        try { new screener.report.KakaoReporter(cfg, db).exchangeCode(code); msg = "카카오 연결 완료. 설정 탭에서 테스트 메시지를 보내 확인하세요."; }
                        catch (Exception e) { msg = "토큰 발급 실패: " + Log.redact(e.getMessage()); }
                    }
                    send(ex, 200, "text/html; charset=utf-8", page(msg));
                    return;
                }
                case "/api/kakao": {
                    screener.report.KakaoReporter k = new screener.report.KakaoReporter(cfg, db);
                    Map<String, Object> m = new LinkedHashMap<>(k.tokenStatus());
                    m.put("enabled_KR", db.setting("kakao_enabled_KR", "false"));
                    m.put("enabled_US", db.setting("kakao_enabled_US", "false"));
                    m.put("redirectUri", cfg.kakaoRedirectUri);
                    m.put("reports", db.query("SELECT id, account, market, trade_date, report_type, status, http_status, error, attempts, created_at, checked_at, sent_at, message_text FROM kakao_reports ORDER BY id DESC LIMIT 30"));
                    m.put("events", db.query("SELECT id, market, trade_date, event_type AS report_type, subject, status, error, attempts, checked_at, sent_at, message_text FROM kakao_events ORDER BY id DESC LIMIT 30"));
                    json(ex, m);
                    return;
                }
                case "/api/kakao/test": {
                    if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "POST only"); return; }
                    Map<String, Object> m = new LinkedHashMap<>();
                    try {
                        screener.report.KakaoReporter.Response r = new screener.report.KakaoReporter(cfg, db).sendText("[모의매매] 테스트 메시지 — 카카오 연결 확인 (" + java.time.LocalDateTime.now().withNano(0) + ")");
                        m.put("ok", r.status() == 200 && r.body().contains("\"result_code\":0"));
                        m.put("message", r.status() == 200 ? "발송 요청 성공 — 카카오톡 '나와의 채팅'을 확인하세요" : "HTTP " + r.status() + " " + Log.redact(r.body()));
                    } catch (Exception e) { m.put("ok", false); m.put("message", Log.redact(e.getMessage())); }
                    json(ex, m);
                    return;
                }
                case "/api/kakao/disconnect": {
                    if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "POST only"); return; }
                    json(ex, Map.of("ok", true, "message", new screener.report.KakaoReporter(cfg, db).disconnect()));
                    return;
                }
                case "/api/reports": json(ex, db.query("SELECT id, market, report_type, period_key, checked_at, text FROM reports ORDER BY period_key DESC, id DESC LIMIT 60")); return;
                case "/api/overview": json(ex, overview()); return;
                case "/api/runs": json(ex, db.query("SELECT id, market, mode, started_at, finished_at, as_of, status, error, completed_day_note FROM runs ORDER BY id DESC LIMIT ?", intq(q, "limit", 30))); return;
                case "/api/run": json(ex, run(q.get("id"))); return;
                case "/api/candidates": json(ex, candidates(q.getOrDefault("market", "KR"), q.getOrDefault("model", "MAS"))); return;
                case "/api/score": json(ex, score(q)); return;
                case "/api/alerts": json(ex, db.query("SELECT id, created_at, model, market, symbol, name, as_of, type, title, body_json, is_read FROM alerts"
                        + ("1".equals(q.get("unread")) ? " WHERE is_read=0" : "") + " ORDER BY id DESC LIMIT 200")); return;
                case "/api/alerts/read": {
                    if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "POST only"); return; }
                    if (q.containsKey("id")) db.markAlertRead(Long.parseLong(q.get("id"))); else db.markAllAlertsRead();
                    json(ex, Map.of("ok", true));
                    return;
                }
                case "/api/tracking": json(ex, tracking(q.getOrDefault("market", "KR"), q.getOrDefault("model", "MAS"))); return;
                case "/api/transitions": json(ex, db.query("SELECT * FROM transitions WHERE market=? ORDER BY as_of DESC, id DESC LIMIT 200", q.getOrDefault("market", "KR"))); return;
                case "/api/msi": json(ex, msi(q.getOrDefault("market", "KR"))); return;
                case "/api/paper": json(ex, paper(q.getOrDefault("market", "KR"))); return;
                case "/api/paper/csv": {
                    String t = q.getOrDefault("table", "paper_fills");
                    if (!t.matches("[a-z_]+")) { send(ex, 400, "text/plain", "bad table"); return; }
                    ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + t + ".csv\"");
                    send(ex, 200, "text/csv; charset=utf-8", screener.engine.PaperExport.csv(db, t));
                    return;
                }
                case "/api/settings": {
                    if ("POST".equals(ex.getRequestMethod())) {
                        Map<String, Object> body = Json.obj(Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                        for (String k : new String[]{"schedule_enabled", "schedule_kr", "schedule_us"}) {
                            if (body.containsKey(k)) {
                                String v = String.valueOf(body.get(k));
                                if (!k.equals("schedule_enabled") && !v.matches("\\d{2}:\\d{2}")) { send(ex, 400, "text/plain", "시각 형식 HH:mm"); return; }
                                db.setSetting(k, v);
                            }
                        }
                        for (String k : new String[]{"kakao_enabled_KR", "kakao_enabled_US"})
                            if (body.containsKey(k)) db.setSetting(k, "true".equals(String.valueOf(body.get(k))) ? "true" : "false");
                        for (String k : PAPER_KEYS.keySet()) {
                            if (!body.containsKey(k)) continue;
                            String v = String.valueOf(body.get(k));
                            if (k.equals("paper_enabled")) { db.setSetting(k, "true".equals(v) ? "true" : "false"); continue; }
                            double d;
                            try { d = Double.parseDouble(v); } catch (NumberFormatException e) { send(ex, 400, "text/plain", k + " 숫자 필요"); return; }
                            if (d < 0) { send(ex, 400, "text/plain", k + " 음수 불가"); return; }
                            db.setSetting(k, v);
                        }
                    }
                    json(ex, settings());
                    return;
                }
                case "/api/trigger": {
                    if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "text/plain", "POST only"); return; }
                    json(ex, trigger(q.getOrDefault("market", "ALL")));
                    return;
                }
                default: send(ex, 404, "text/plain", "not found");
            }
        } catch (Exception e) {
            Log.warn("대시보드 오류: " + e.getMessage());
            send(ex, 500, "application/json", Json.write(Map.of("error", Log.redact(String.valueOf(e.getMessage())))));
        }
    }

    private Map<String, Object> overview() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", cfg.demo ? "demo" : "live");
        m.put("dbBackend", db.describe());
        m.put("schemaVersion", db.schemaVersion());
        m.put("instanceRole", db.setting("instance_role", ""));
        m.put("db", cfg.dbPath.toString());
        m.put("keyStatus", cfg.keyStatus());
        m.put("running", Runner.isRunning());
        Map<String, Object> mk = new LinkedHashMap<>();
        for (String market : new String[]{"KR", "US"}) {
            Map<String, Object> x = new LinkedHashMap<>();
            List<Map<String, Object>> r = db.query("SELECT id, status, as_of, started_at, finished_at, completed_day_note, summary_json, sources_json, error FROM runs WHERE market=? ORDER BY id DESC LIMIT 1", market);
            if (!r.isEmpty()) {
                Map<String, Object> run = r.get(0);
                run.put("summary", parse(run.remove("summary_json")));
                run.put("sources", parse(run.remove("sources_json")));
                x.put("lastRun", run);
            }
            List<Map<String, Object>> ok = db.query("SELECT finished_at, as_of FROM runs WHERE market=? AND status IN ('OK','PARTIAL') ORDER BY id DESC LIMIT 1", market);
            x.put("lastSuccess", ok.isEmpty() ? null : ok.get(0));
            List<Map<String, Object>> ms = db.query("SELECT detail_json FROM msi WHERE market=? ORDER BY as_of DESC LIMIT 1", market);
            x.put("msi", ms.isEmpty() ? null : parse(ms.get(0).get("detail_json")));
            String as = latestAsOf(market);
            x.put("asOf", as);
            if (as != null) {
                x.put("masCounts", db.query("SELECT status, COUNT(*) n FROM scores WHERE market=? AND model='MAS' AND as_of=? GROUP BY status", market, as));
                x.put("oisCounts", db.query("SELECT status, COUNT(*) n FROM scores WHERE market=? AND model='OIS' AND as_of=? GROUP BY status", market, as));
            }
            mk.put(market, x);
        }
        m.put("markets", mk);
        m.put("unreadAlerts", db.count("SELECT COUNT(*) FROM alerts WHERE is_read=0"));
        m.put("ruleVersions", List.of(Rules.MAS_VERSION, Rules.OIS_VERSION, Rules.MSI_VERSION));
        m.put("appRules", List.of(Rules.APP_RULES));
        m.put("settings", settings());
        return m;
    }

    private String latestAsOf(String market) throws Exception {
        List<Map<String, Object>> r = db.query("SELECT MAX(as_of) a FROM scores WHERE market=?", market);
        return r.isEmpty() ? null : (String) r.get(0).get("a");
    }

    private Map<String, Object> run(String id) throws Exception {
        List<Map<String, Object>> r = db.query("SELECT * FROM runs WHERE id=?", Long.parseLong(id));
        if (r.isEmpty()) return Map.of();
        Map<String, Object> run = r.get(0);
        run.put("summary", parse(run.remove("summary_json")));
        run.put("sources", parse(run.remove("sources_json")));
        return run;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> candidates(String market, String model) throws Exception {
        String as = latestAsOf(market);
        List<Map<String, Object>> out = new ArrayList<>();
        if (as == null) return out;
        for (Map<String, Object> r : db.query("SELECT symbol, name, as_of, s, w, coverage, range_high, status, confidence, card_json FROM scores WHERE market=? AND model=? AND as_of=?"
                + " ORDER BY s DESC", market, model, as)) {
            Map<String, Object> card = Json.obj(parse(r.remove("card_json")));
            if (card != null) {
                Map<String, Object> info = Json.obj(card.get("info"));
                r.put("statusLabel", card.get("statusLabel"));
                r.put("statusReason", card.get("statusReason"));
                r.put("qualifier", card.get("gradeQualifier"));
                r.put("validAreas", card.get("validAreas"));
                r.put("price", info.get("price"));
                r.put("ret20", info.get("ret20"));
                r.put("ret60", info.get("ret60"));
                r.put("drawdown60", info.get("drawdown60"));
                r.put("marketCap", info.get("marketCap"));
                r.put("board", info.get("board"));
                r.put("declineType", info.get("declineType"));
                r.put("pros", ((List<Object>) card.get("pros")).size());
                r.put("cons", ((List<Object>) card.get("cons")).size());
                r.put("missing", ((List<Object>) card.get("missing")).size());
            }
            r.put("statusLabel", r.get("statusLabel") == null ? Rules.label((String) r.get("status")) : r.get("statusLabel"));
            out.add(r);
        }
        return out;
    }

    private Map<String, Object> score(Map<String, String> q) throws Exception {
        String model = q.get("model"), market = q.get("market"), sym = q.get("symbol"), as = q.get("asOf");
        if (as == null) as = latestAsOf(market);
        List<Map<String, Object>> r = db.query("SELECT card_json FROM scores WHERE model=? AND market=? AND symbol=? AND as_of=?", model, market, sym, as);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("card", r.isEmpty() ? null : parse(r.get(0).get("card_json")));
        m.put("transitions", db.query("SELECT as_of, from_state, to_state, s, reason FROM transitions WHERE model=? AND market=? AND symbol=? ORDER BY as_of DESC LIMIT 30", model, market, sym));
        m.put("history", db.query("SELECT as_of, s, w, status FROM scores WHERE model=? AND market=? AND symbol=? ORDER BY as_of DESC LIMIT 60", model, market, sym));
        List<Map<String, Object>> tr = db.query("SELECT * FROM tracking WHERE model=? AND market=? AND symbol=? ORDER BY id DESC", model, market, sym);
        for (Map<String, Object> t : tr) t.put("checkpoints", db.query("SELECT * FROM checkpoints WHERE tracking_id=? ORDER BY horizon", t.get("id")));
        m.put("tracking", tr);
        return m;
    }

    private List<Map<String, Object>> tracking(String market, String model) throws Exception {
        List<Map<String, Object>> tr = db.query("SELECT * FROM tracking WHERE market=? AND model=? ORDER BY status, detect_date DESC LIMIT 300", market, model);
        for (Map<String, Object> t : tr) t.put("checkpoints", db.query("SELECT horizon, date, ret, mfe, mae, excess, sector_excess, score_at, state_at, hit10, hit15 FROM checkpoints WHERE tracking_id=? ORDER BY horizon", t.get("id")));
        return tr;
    }

    private Map<String, Object> msi(String market) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> r = db.query("SELECT detail_json FROM msi WHERE market=? ORDER BY as_of DESC LIMIT 1", market);
        m.put("latest", r.isEmpty() ? null : parse(r.get(0).get("detail_json")));
        m.put("history", db.query("SELECT as_of, score, band, dynamic, confidence, provisional FROM msi WHERE market=? ORDER BY as_of DESC LIMIT 120", market));
        m.put("news", db.query("SELECT date, score, raw_score, status, note, n_raw, n_kept, n_dup, n_ads, n_outside, outlets, levels_json, intensity, growth, window_from, window_to, queries, calls, fetched_at, rule_version"
                + " FROM news_daily WHERE market=? ORDER BY date DESC LIMIT 10", market));
        return m;
    }

    /** 모의매매 설정 키 → [기본값, 설명]. 시작금은 계좌 최초 생성 시에만 적용. */
    static final Map<String, String[]> PAPER_KEYS = new LinkedHashMap<>();
    static {
        PAPER_KEYS.put("paper_enabled", new String[]{"true", "모의매매 사용"});
        PAPER_KEYS.put("paper_start_KRW", new String[]{"100000000", "한국 가상 시작금(원, 계좌 생성 시)"});
        PAPER_KEYS.put("paper_start_USD", new String[]{"100000", "미국 가상 시작금(달러, 계좌 생성 시)"});
        PAPER_KEYS.put("paper_plan_pct", new String[]{"0.10", "종목별 계획금(시작금 대비)"});
        PAPER_KEYS.put("paper_max_symbol_pct", new String[]{"0.15", "종목별 한도(시작금 대비, 취득원가 기준)"});
        PAPER_KEYS.put("paper_cash_reserve_pct", new String[]{"0.20", "현금 유보(시작금 대비)"});
        PAPER_KEYS.put("paper_fee_buy_KR", new String[]{"0.00015", "한국 매수 수수료율"});
        PAPER_KEYS.put("paper_fee_sell_KR", new String[]{"0.00015", "한국 매도 수수료율"});
        PAPER_KEYS.put("paper_tax_sell_KR", new String[]{"0.0018", "한국 매도 세금 가정"});
        PAPER_KEYS.put("paper_slip_bps_KR", new String[]{"10", "한국 슬리피지(bp)"});
        PAPER_KEYS.put("paper_fee_buy_US", new String[]{"0", "미국 매수 수수료율"});
        PAPER_KEYS.put("paper_fee_sell_US", new String[]{"0", "미국 매도 수수료율"});
        PAPER_KEYS.put("paper_tax_sell_US", new String[]{"0", "미국 매도 규제 수수료 가정"});
        PAPER_KEYS.put("paper_slip_bps_US", new String[]{"5", "미국 슬리피지(bp)"});
    }

    private Map<String, Object> paper(String market) throws Exception {
        String acc = market + "-" + ("KR".equals(market) ? "KRW" : "USD");
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> a = db.query("SELECT * FROM paper_accounts WHERE account=?", acc);
        m.put("account", a.isEmpty() ? null : a.get(0));
        List<Map<String, Object>> eq = db.query("SELECT * FROM paper_equity_daily WHERE account=? ORDER BY date", acc);
        m.put("equity", eq);
        List<Map<String, Object>> pos = db.query("SELECT * FROM paper_positions WHERE account=? AND qty>0 ORDER BY symbol", acc);
        for (Map<String, Object> p : pos) {
            List<Map<String, Object>> mk = db.query("SELECT mark_price, mark_date FROM paper_fills WHERE account=? AND symbol=? AND mark_price IS NOT NULL ORDER BY id DESC LIMIT 1", acc, p.get("symbol"));
            if (!mk.isEmpty()) { p.put("mark_price", mk.get(0).get("mark_price")); p.put("mark_date", mk.get(0).get("mark_date")); }
        }
        m.put("positions", pos);
        m.put("orders", db.query("SELECT o.*, (SELECT reason FROM paper_signals s WHERE s.id=o.signal_id) AS signal_reason FROM paper_orders o WHERE account=? ORDER BY id DESC LIMIT 200", acc));
        m.put("fills", db.query("SELECT id, order_id, signal_id, side, stage, symbol, name, fill_date, prev_close, ref_open, gap, fill_price, qty, fee, tax, slippage_cost, net_cash, realized_pnl,"
                + " day_volume, participation_day, participation_window, auction_volume, bid, ask, vol_1m, vol_5m, liquidity_assumption, net_ret_mark FROM paper_fills WHERE account=? ORDER BY id DESC LIMIT 200", acc));
        m.put("deferred", db.query("SELECT id, signal_date, symbol, name, action, reason, msi, ois_s, ois_w, ois_coverage FROM paper_signals WHERE account=? AND decision='DEFER' ORDER BY id DESC LIMIT 200", acc));
        m.put("performance", db.query("SELECT * FROM paper_performance WHERE market=? AND as_of=(SELECT MAX(as_of) FROM paper_performance WHERE market=?) ORDER BY dimension, bucket", market, market));
        List<String> tables = new ArrayList<>(screener.engine.PaperExport.TABLES.keySet());
        tables.add("paper_equity_combined");
        m.put("csvTables", tables);
        m.put("strategy", screener.engine.PaperTrader.VERSION_DESC);
        m.put("liquidityAssumption", screener.engine.PaperTrader.LIQ_ASSUMPTION);
        return m;
    }

    private Map<String, Object> settings() throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> pk = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : PAPER_KEYS.entrySet()) pk.put(e.getKey(), Map.of("value", db.setting(e.getKey(), e.getValue()[0]), "label", e.getValue()[1]));
        m.put("paper", pk);
        m.put("schedule_enabled", db.setting("schedule_enabled", "false"));
        m.put("schedule_kr", db.setting("schedule_kr", "18:30"));
        m.put("schedule_us", db.setting("schedule_us", "07:00"));
        m.put("schedule_last_kr", db.setting("schedule_last_kr", ""));
        m.put("schedule_last_us", db.setting("schedule_last_us", ""));
        m.put("schedulerActive", !cfg.demo);
        return m;
    }

    private Map<String, Object> trigger(String market) {
        Map<String, Object> m = new HashMap<>();
        if (cfg.demo) { m.put("ok", false); m.put("message", "데모 모드에서는 실데이터 실행을 시작하지 않습니다 — `demo` 명령 사용"); return m; }
        if (Runner.isRunning()) { m.put("ok", false); m.put("message", "이미 실행 중"); return m; }
        try { Runner.assertActive(db); } catch (Exception e) { m.put("ok", false); m.put("message", e.getMessage()); return m; }
        List<String> ms = new ArrayList<>();
        if (market.equals("ALL") || market.equals("KR")) ms.add("KR");
        if (market.equals("ALL") || market.equals("US")) ms.add("US");
        Thread t = new Thread(() -> {
            try { Runner.runLive(cfg, db, ms); } catch (Exception e) { Log.warn("수동 실행 실패: " + Log.redact(e.getMessage())); }
        }, "manual-run");
        t.setDaemon(true);
        t.start();
        lastManualRun = String.join(",", ms);
        m.put("ok", true);
        m.put("message", lastManualRun + " 실행 시작 — 실행 내역에서 진행 상태 확인");
        return m;
    }

    private static String page(String msg) {
        return "<!doctype html><meta charset='utf-8'><title>카카오 연결</title><body style='font-family:sans-serif;padding:24px'><p>"
                + msg.replace("<", "&lt;") + "</p><p><a href='/'>대시보드로 돌아가기</a></p></body>";
    }

    private static Object parse(Object s) { return s == null ? null : Json.parse(s.toString()); }

    private static int intq(Map<String, String> q, String k, int def) {
        try { return Integer.parseInt(q.getOrDefault(k, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void json(HttpExchange ex, Object o) throws IOException { send(ex, 200, "application/json; charset=utf-8", Json.write(o)); }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
