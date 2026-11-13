package screener.store;

import screener.engine.ScoreCard;
import screener.model.Bar;
import screener.model.FlowDay;
import screener.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 저장소. Windows 개발 기본은 SQLite 파일, 운영(Lightsail Linux)은 MariaDB(SCREENER_DB_URL 설정 시).
 * 스키마는 {@link Schema} 의 버전별 마이그레이션으로 관리하고, SQLite 문법으로 쓴 DML은 {@link Schema#dml} 로 방언 변환한다.
 * 재실행 시 중복 저장을 막기 위해 자연키에 UNIQUE 제약과 UPSERT를 사용한다. API 키는 어떤 테이블에도 저장하지 않는다.
 */
public final class Db implements AutoCloseable {
    private Connection c;
    private final Schema.Dialect dialect;
    private final String url, user, password;
    private long lastValidated = System.currentTimeMillis();

    /** SQLite 파일(개발·데모·테스트). */
    public Db(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");
        this.dialect = Schema.Dialect.SQLITE;
        this.url = "jdbc:sqlite:" + file.toAbsolutePath();
        this.user = null;
        this.password = null;
        c = connect();
        migrate();
    }

    /** MariaDB(운영). url 예: jdbc:mariadb://127.0.0.1:3306/screener */
    public Db(String url, String user, String password) throws Exception {
        if (!url.startsWith("jdbc:mariadb:")) throw new IllegalArgumentException("SCREENER_DB_URL 은 jdbc:mariadb:// 형식이어야 합니다");
        Class.forName("org.mariadb.jdbc.Driver");
        this.dialect = Schema.Dialect.MARIADB;
        this.url = url;
        this.user = user;
        this.password = password;
        c = connect();
        migrate();
    }

    /** 설정에 따라 연결: 데모는 항상 SQLite(data/demo.db), SCREENER_DB_URL 이 있으면 MariaDB, 없으면 SQLite(data/screener.db). */
    public static Db open(screener.Config cfg) throws Exception {
        if (!cfg.demo && cfg.dbUrl != null) return new Db(cfg.dbUrl, cfg.dbUser, cfg.dbPassword);
        return new Db(cfg.dbPath);
    }

    public Schema.Dialect dialect() { return dialect; }

    /** 로그용 연결 설명(비밀번호 제외). */
    public String describe() {
        return dialect == Schema.Dialect.MARIADB ? "MariaDB " + url.replaceAll("[?].*$", "") + " (user " + user + ")" : "SQLite " + url.substring("jdbc:sqlite:".length());
    }

    private Connection connect() throws SQLException {
        Connection x;
        if (dialect == Schema.Dialect.SQLITE) {
            x = DriverManager.getConnection(url);
            try (Statement st = x.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=10000");
                st.execute("PRAGMA foreign_keys=ON");
            }
        } else {
            x = DriverManager.getConnection(url, user, password);
            try (Statement st = x.createStatement()) {
                st.execute("SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ENGINE_SUBSTITUTION'");
                st.execute("SET SESSION time_zone='+00:00'");
            }
        }
        return x;
    }

    /** 유휴로 끊긴 MariaDB 연결을 트랜잭션 밖에서만 재연결. */
    private synchronized Connection c() throws SQLException {
        if (dialect == Schema.Dialect.MARIADB && c.getAutoCommit() && System.currentTimeMillis() - lastValidated > 60_000) {
            if (!c.isValid(3)) {
                try { c.close(); } catch (SQLException ignored) { }
                c = connect();
            }
            lastValidated = System.currentTimeMillis();
        }
        return c;
    }

    public synchronized PreparedStatement prepare(String sql) throws SQLException { return c().prepareStatement(Schema.dml(sql, dialect)); }

    public synchronized PreparedStatement prepare(String sql, int keys) throws SQLException { return c().prepareStatement(Schema.dml(sql, dialect), keys); }

    // ---------- 스키마 ----------
    /** 미적용 버전만 순서대로 적용하고 schema_migrations 에 기록. 모든 단계는 재실행 안전. */
    public synchronized List<Integer> migrate() throws SQLException {
        List<Integer> applied = new ArrayList<>();
        try (Statement st = c().createStatement()) {
            st.execute(Schema.ddl("CREATE TABLE IF NOT EXISTS schema_migrations(version %I% PRIMARY KEY, description %T%, applied_at %K32%)", dialect));
        }
        java.util.Set<Integer> done = new java.util.HashSet<>();
        try (Statement st = c().createStatement(); ResultSet r = st.executeQuery("SELECT version FROM schema_migrations")) {
            while (r.next()) done.add(r.getInt(1));
        }
        for (Schema.Migration m : Schema.MIGRATIONS) {
            if (done.contains(m.version())) continue;
            for (Schema.Step step : m.steps()) {
                if (step.addColumnTable() != null && hasColumn(step.addColumnTable(), step.addColumnName())) continue;
                try (Statement st = c().createStatement()) { st.execute(Schema.ddl(step.sql(), dialect)); }
            }
            try (PreparedStatement ps = c().prepareStatement("INSERT INTO schema_migrations(version, description, applied_at) VALUES(?,?,?)")) {
                ps.setInt(1, m.version()); ps.setString(2, m.description()); ps.setString(3, now());
                ps.executeUpdate();
            }
            applied.add(m.version());
        }
        return applied;
    }

    public synchronized boolean hasColumn(String table, String column) throws SQLException {
        try (Statement st = c().createStatement(); ResultSet r = st.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData md = r.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) if (md.getColumnName(i).equalsIgnoreCase(column)) return true;
        }
        return false;
    }

    public synchronized int schemaVersion() throws SQLException {
        try (Statement st = c().createStatement(); ResultSet r = st.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    @Override public void close() throws SQLException { c.close(); }

    public Connection conn() throws SQLException { return c(); }

    // ---------- 실행 ----------
    public synchronized long startRun(String market, String mode) throws SQLException {
        try (PreparedStatement p = prepare("INSERT INTO runs(market, mode, started_at, status) VALUES(?,?,?, 'RUNNING')", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, market); p.setString(2, mode); p.setString(3, now());
            p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) { r.next(); return r.getLong(1); }
        }
    }

    public synchronized void finishRun(long id, String status, String asOf, String note, Map<String, Object> summary, Object sources, String error) throws SQLException {
        try (PreparedStatement p = prepare("UPDATE runs SET finished_at=?, status=?, as_of=?, completed_day_note=?, summary_json=?, sources_json=?, error=? WHERE id=?")) {
            p.setString(1, now()); p.setString(2, status); p.setString(3, asOf); p.setString(4, note);
            p.setString(5, Json.write(summary)); p.setString(6, Json.write(sources)); p.setString(7, error); p.setLong(8, id);
            p.executeUpdate();
        }
    }

    /** 비정상 종료로 남은 RUNNING 기록 정리. */
    public synchronized void markStaleRuns() throws SQLException {
        try (Statement s = c().createStatement()) {
            s.executeUpdate("UPDATE runs SET status='ABORTED', finished_at='" + now() + "' WHERE status='RUNNING'");
        }
    }

    // ---------- 점수 ----------
    public synchronized void upsertScore(long runId, ScoreCard card) throws SQLException {
        String sql = "INSERT INTO scores(run_id, model, market, symbol, name, as_of, s, w, coverage, range_high, status, confidence, rule_version, card_json)" +
                " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(model, market, symbol, as_of) DO UPDATE SET run_id=excluded.run_id, name=excluded.name," +
                " s=excluded.s, w=excluded.w, coverage=excluded.coverage, range_high=excluded.range_high, status=excluded.status," +
                " confidence=excluded.confidence, rule_version=excluded.rule_version, card_json=excluded.card_json";
        try (PreparedStatement p = prepare(sql)) {
            p.setLong(1, runId); p.setString(2, card.model); p.setString(3, card.market); p.setString(4, card.symbol); p.setString(5, card.name);
            p.setString(6, card.asOf); p.setDouble(7, card.S()); p.setDouble(8, card.W()); p.setDouble(9, card.coverage()); p.setDouble(10, card.rangeHigh());
            p.setString(11, card.status); p.setString(12, card.confidence); p.setString(13, card.ruleVersion); p.setString(14, Json.write(card.toMap()));
            p.executeUpdate();
        }
    }

    public synchronized Double scoreOn(String model, String market, String symbol, String date) throws SQLException {
        try (PreparedStatement p = prepare("SELECT s FROM scores WHERE model=? AND market=? AND symbol=? AND as_of<=? ORDER BY as_of DESC LIMIT 1")) {
            p.setString(1, model); p.setString(2, market); p.setString(3, symbol); p.setString(4, date);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getDouble(1) : null; }
        }
    }

    public synchronized String stateOn(String model, String market, String symbol, String date) throws SQLException {
        try (PreparedStatement p = prepare("SELECT status FROM scores WHERE model=? AND market=? AND symbol=? AND as_of<=? ORDER BY as_of DESC LIMIT 1")) {
            p.setString(1, model); p.setString(2, market); p.setString(3, symbol); p.setString(4, date);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    // ---------- 상태 ----------
    public record StateRow(String state, String lastAsOf, String prevState, String prevAsOf, String since) { }

    public synchronized StateRow state(String model, String market, String symbol) throws SQLException {
        try (PreparedStatement p = prepare("SELECT state, last_as_of, prev_state, prev_as_of, since FROM candidate_state WHERE model=? AND market=? AND symbol=?")) {
            p.setString(1, model); p.setString(2, market); p.setString(3, symbol);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? new StateRow(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)) : null;
            }
        }
    }

    /** 이번 기준일 판정의 기준이 되는 '직전' 상태. 같은 기준일 재실행이면 그 이전 상태를 돌려줘 결과가 흔들리지 않게 한다. */
    public synchronized String baselineState(String model, String market, String symbol, String asOf) throws SQLException {
        StateRow s = state(model, market, symbol);
        if (s == null) return null;
        if (asOf.equals(s.lastAsOf())) return s.prevState();
        return s.state();
    }

    /** 상태 저장 + 전환 기록(같은 기준일 재실행은 덮어쓰기). 반환: 실제 전환 여부. */
    public synchronized boolean setState(String model, String market, String symbol, String name, String baseline, String newState, String asOf, double s, String reason) throws SQLException {
        StateRow cur = state(model, market, symbol);
        if (cur == null) {
            try (PreparedStatement p = prepare("INSERT INTO candidate_state(model, market, symbol, name, state, since, last_as_of, last_s, prev_state, prev_as_of) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                p.setString(1, model); p.setString(2, market); p.setString(3, symbol); p.setString(4, name); p.setString(5, newState);
                p.setString(6, asOf); p.setString(7, asOf); p.setDouble(8, s); p.setString(9, null); p.setString(10, null);
                p.executeUpdate();
            }
        } else if (asOf.equals(cur.lastAsOf())) {
            String since = newState.equals(cur.state()) ? cur.since() : (newState.equals(baseline) ? cur.since() : asOf);
            try (PreparedStatement p = prepare("UPDATE candidate_state SET state=?, last_s=?, name=?, since=? WHERE model=? AND market=? AND symbol=?")) {
                p.setString(1, newState); p.setDouble(2, s); p.setString(3, name); p.setString(4, since);
                p.setString(5, model); p.setString(6, market); p.setString(7, symbol);
                p.executeUpdate();
            }
        } else {
            String since = newState.equals(cur.state()) ? cur.since() : asOf;
            try (PreparedStatement p = prepare("UPDATE candidate_state SET prev_state=state, prev_as_of=last_as_of, state=?, last_as_of=?, last_s=?, name=?, since=? WHERE model=? AND market=? AND symbol=?")) {
                p.setString(1, newState); p.setString(2, asOf); p.setDouble(3, s); p.setString(4, name); p.setString(5, since);
                p.setString(6, model); p.setString(7, market); p.setString(8, symbol);
                p.executeUpdate();
            }
        }
        boolean changed = !newState.equals(baseline == null ? "NONE" : baseline);
        if (changed) {
            try (PreparedStatement p = prepare("INSERT INTO transitions(model, market, symbol, name, as_of, from_state, to_state, s, reason) VALUES(?,?,?,?,?,?,?,?,?)" +
                    " ON CONFLICT(model, market, symbol, as_of) DO UPDATE SET from_state=excluded.from_state, to_state=excluded.to_state, s=excluded.s, reason=excluded.reason")) {
                p.setString(1, model); p.setString(2, market); p.setString(3, symbol); p.setString(4, name); p.setString(5, asOf);
                p.setString(6, baseline == null ? "NONE" : baseline); p.setString(7, newState); p.setDouble(8, s); p.setString(9, reason);
                p.executeUpdate();
            }
        } else {
            try (PreparedStatement p = prepare("DELETE FROM transitions WHERE model=? AND market=? AND symbol=? AND as_of=?")) {
                p.setString(1, model); p.setString(2, market); p.setString(3, symbol); p.setString(4, asOf);
                p.executeUpdate();
            }
        }
        return changed;
    }

    public synchronized List<String> statesIn(String model, String market, java.util.Collection<String> states) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement p = prepare("SELECT symbol, state FROM candidate_state WHERE model=? AND market=?")) {
            p.setString(1, model); p.setString(2, market);
            try (ResultSet r = p.executeQuery()) { while (r.next()) if (states.contains(r.getString(2))) out.add(r.getString(1)); }
        }
        return out;
    }

    // ---------- 알림 ----------
    public synchronized boolean addAlert(String model, String market, String symbol, String name, String asOf, String type, String title, Map<String, Object> body) throws SQLException {
        try (PreparedStatement p = prepare("INSERT OR IGNORE INTO alerts(created_at, model, market, symbol, name, as_of, type, title, body_json) VALUES(?,?,?,?,?,?,?,?,?)")) {
            p.setString(1, now()); p.setString(2, model); p.setString(3, market); p.setString(4, symbol); p.setString(5, name);
            p.setString(6, asOf); p.setString(7, type); p.setString(8, title); p.setString(9, Json.write(body));
            return p.executeUpdate() > 0;
        }
    }

    public synchronized void markAlertRead(long id) throws SQLException {
        try (PreparedStatement p = prepare("UPDATE alerts SET is_read=1 WHERE id=?")) { p.setLong(1, id); p.executeUpdate(); }
    }

    public synchronized void markAllAlertsRead() throws SQLException {
        try (Statement s = c().createStatement()) { s.executeUpdate("UPDATE alerts SET is_read=1"); }
    }

    // ---------- MSI ----------
    public synchronized void upsertMsi(long runId, Map<String, Object> m) throws SQLException {
        String sql = "INSERT INTO msi(market, as_of, score, w, provisional, band, dynamic, ch1, ch5, ch20, confidence, rule_version, run_id, detail_json)" +
                " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(market, as_of) DO UPDATE SET score=excluded.score, w=excluded.w, provisional=excluded.provisional," +
                " band=excluded.band, dynamic=excluded.dynamic, ch1=excluded.ch1, ch5=excluded.ch5, ch20=excluded.ch20, confidence=excluded.confidence," +
                " rule_version=excluded.rule_version, run_id=excluded.run_id, detail_json=excluded.detail_json";
        try (PreparedStatement p = prepare(sql)) {
            p.setString(1, (String) m.get("market")); p.setString(2, (String) m.get("asOf"));
            setD(p, 3, m.get("score")); setD(p, 4, m.get("W"));
            p.setInt(5, Boolean.TRUE.equals(m.get("provisional")) ? 1 : 0);
            p.setString(6, (String) m.get("band")); p.setString(7, (String) m.get("dynamic"));
            setD(p, 8, m.get("ch1")); setD(p, 9, m.get("ch5")); setD(p, 10, m.get("ch20"));
            p.setString(11, (String) m.get("confidence")); p.setString(12, (String) m.get("ruleVersion"));
            p.setLong(13, runId); p.setString(14, Json.write(m));
            p.executeUpdate();
        }
    }

    public synchronized Map<String, Object> msiBefore(String market, String asOf) throws SQLException {
        List<Map<String, Object>> r = query("SELECT * FROM msi WHERE market=? AND as_of<? AND score IS NOT NULL ORDER BY as_of DESC LIMIT 1", market, asOf);
        return r.isEmpty() ? null : r.get(0);
    }

    private static void setD(PreparedStatement p, int i, Object v) throws SQLException {
        if (v instanceof Number) p.setDouble(i, ((Number) v).doubleValue()); else p.setNull(i, java.sql.Types.REAL);
    }

    // ---------- 추적 ----------
    public synchronized Long activeTracking(String model, String market, String symbol) throws SQLException {
        try (PreparedStatement p = prepare("SELECT id FROM tracking WHERE model=? AND market=? AND symbol=? AND status='ACTIVE'")) {
            p.setString(1, model); p.setString(2, market); p.setString(3, symbol);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getLong(1) : null; }
        }
    }

    /** 기준일 D 이전부터 추적 중이던 기록(D에 종료된 것 포함). 같은 기준일 재실행에서도 판정이 바뀌지 않도록 한다. */
    public synchronized Long trackingAsOf(String model, String market, String symbol, String d) throws SQLException {
        try (PreparedStatement p = prepare("SELECT id FROM tracking WHERE model=? AND market=? AND symbol=? AND detect_date<?"
                + " AND (status='ACTIVE' OR end_as_of>=?) ORDER BY detect_date DESC LIMIT 1")) {
            p.setString(1, model); p.setString(2, market); p.setString(3, symbol); p.setString(4, d); p.setString(5, d);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getLong(1) : null; }
        }
    }

    public synchronized boolean createTracking(String model, String market, String symbol, String name, String date, double close, double score, String state, String bench, Double benchClose) throws SQLException {
        try (PreparedStatement p = prepare("INSERT OR IGNORE INTO tracking(model, market, symbol, name, detect_date, detect_close, detect_score, detect_state, bench, bench_close, status, last_update)" +
                " VALUES(?,?,?,?,?,?,?,?,?,?, 'ACTIVE', ?)")) {
            p.setString(1, model); p.setString(2, market); p.setString(3, symbol); p.setString(4, name); p.setString(5, date);
            p.setDouble(6, close); p.setDouble(7, score); p.setString(8, state); p.setString(9, bench); setD(p, 10, benchClose); p.setString(11, now());
            return p.executeUpdate() > 0;
        }
    }

    public synchronized List<Map<String, Object>> activeTrackings(String model, String market) throws SQLException {
        return query("SELECT * FROM tracking WHERE model=? AND market=? AND status='ACTIVE'", model, market);
    }

    /** 활성 추적 + 기준일 d에 종료된 추적(재실행 시 동일 처리용). */
    public synchronized List<Map<String, Object>> trackingsFor(String model, String market, String d) throws SQLException {
        return query("SELECT * FROM tracking WHERE model=? AND market=? AND (status='ACTIVE' OR end_as_of=?)", model, market, d);
    }

    public synchronized boolean addCheckpoint(long tid, int horizon, String date, double close, double ret, double mfe, double mae, Double benchRet, Double excess,
                                              Double sectorExcess, Double scoreAt, String stateAt) throws SQLException {
        try (PreparedStatement p = prepare("INSERT OR IGNORE INTO checkpoints(tracking_id, horizon, date, close, ret, mfe, mae, bench_ret, excess, sector_excess, score_at, state_at, hit10, hit15)" +
                " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            p.setLong(1, tid); p.setInt(2, horizon); p.setString(3, date); p.setDouble(4, close); p.setDouble(5, ret); p.setDouble(6, mfe); p.setDouble(7, mae);
            setD(p, 8, benchRet); setD(p, 9, excess); setD(p, 10, sectorExcess); setD(p, 11, scoreAt); p.setString(12, stateAt);
            p.setInt(13, mfe >= 0.10 ? 1 : 0); p.setInt(14, mfe >= 0.15 ? 1 : 0);
            return p.executeUpdate() > 0;
        }
    }

    public synchronized void endTracking(long tid, String reason, String asOf) throws SQLException {
        try (PreparedStatement p = prepare("UPDATE tracking SET status='ENDED', end_reason=?, end_as_of=?, last_update=? WHERE id=?")) {
            p.setString(1, reason); p.setString(2, asOf); p.setString(3, now()); p.setLong(4, tid); p.executeUpdate();
        }
    }

    public synchronized void setInvalidReason(long tid, String reason) throws SQLException {
        try (PreparedStatement p = prepare("UPDATE tracking SET invalid_reason=COALESCE(invalid_reason, ?), last_update=? WHERE id=?")) {
            p.setString(1, reason); p.setString(2, now()); p.setLong(3, tid); p.executeUpdate();
        }
    }

    // ---------- 원천 시세 캐시 ----------
    public synchronized String krxDateStatus(String date) throws SQLException {
        try (PreparedStatement p = prepare("SELECT status, checked_at FROM krx_dates WHERE date=?")) {
            p.setString(1, date);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) + "|" + r.getString(2) : null; }
        }
    }

    public synchronized void setKrxDate(String date, String status, int rows) throws SQLException {
        try (PreparedStatement p = prepare("INSERT OR REPLACE INTO krx_dates(date, status, `rows`, checked_at) VALUES(?,?,?,?)")) {
            p.setString(1, date); p.setString(2, status); p.setInt(3, rows); p.setString(4, now()); p.executeUpdate();
        }
    }

    public synchronized void insertKrxRows(List<Object[]> rows) throws SQLException {
        boolean ac = c().getAutoCommit();
        c().setAutoCommit(false);
        try (PreparedStatement p = prepare("INSERT OR REPLACE INTO krx_daily(date, code, market, name, sect, open, high, low, close, volume, value, mcap, shares) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (Object[] r : rows) {
                for (int i = 0; i < r.length; i++) {
                    if (r[i] instanceof Double) p.setDouble(i + 1, (Double) r[i]);
                    else if (r[i] == null) p.setNull(i + 1, java.sql.Types.NULL);
                    else p.setString(i + 1, r[i].toString());
                }
                p.addBatch();
            }
            p.executeBatch();
            c().commit();
        } catch (SQLException e) {
            c().rollback();
            throw e;
        } finally {
            c().setAutoCommit(ac);
        }
    }

    public synchronized List<String> krxOpenDates(String maxDate, int limit) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement p = prepare("SELECT date FROM krx_dates WHERE status='OPEN' AND date<=? ORDER BY date DESC LIMIT ?")) {
            p.setString(1, maxDate); p.setInt(2, limit);
            try (ResultSet r = p.executeQuery()) { while (r.next()) out.add(r.getString(1)); }
        }
        return out;
    }

    public synchronized Map<String, Object> krxRow(String date, String code) throws SQLException {
        List<Map<String, Object>> r = query("SELECT * FROM krx_daily WHERE date=? AND code=?", date, code);
        return r.isEmpty() ? null : r.get(0);
    }

    public synchronized List<Map<String, Object>> krxRowsOn(String date) throws SQLException {
        return query("SELECT * FROM krx_daily WHERE date=?", date);
    }

    public synchronized Map<String, List<Bar>> krxBars(java.util.Collection<String> codes, String fromDate, String toDate) throws SQLException {
        Map<String, List<Bar>> out = new LinkedHashMap<>();
        try (PreparedStatement p = prepare("SELECT date, open, high, low, close, volume, value, shares FROM krx_daily WHERE code=? AND date>=? AND date<=? ORDER BY date")) {
            for (String code : codes) {
                p.setString(1, code); p.setString(2, fromDate); p.setString(3, toDate);
                List<Bar> l = new ArrayList<>();
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        double o = r.getDouble(2), h = r.getDouble(3), lo = r.getDouble(4), cl = r.getDouble(5), v = r.getDouble(6);
                        if (cl <= 0) continue;
                        if (o <= 0) { o = cl; h = cl; lo = cl; } // 거래 없는 날: 시가 0 → 종가로 대체(거래량 0)
                        l.add(new Bar(LocalDate.parse(r.getString(1)), o, h, lo, cl, v, r.getDouble(7), false, r.getDouble(8)));
                    }
                }
                if (!l.isEmpty()) out.put(code, l);
            }
        }
        return out;
    }

    public synchronized void upsertUsBars(String symbol, List<Bar> bars, String src) throws SQLException {
        boolean ac = c().getAutoCommit();
        c().setAutoCommit(false);
        try (PreparedStatement p = prepare("INSERT OR REPLACE INTO us_bars(symbol, date, open, high, low, close, volume, src) VALUES(?,?,?,?,?,?,?,?)")) {
            for (Bar b : bars) {
                p.setString(1, symbol); p.setString(2, b.date().toString()); p.setDouble(3, b.open()); p.setDouble(4, b.high());
                p.setDouble(5, b.low()); p.setDouble(6, b.close()); p.setDouble(7, b.volume()); p.setString(8, src);
                p.addBatch();
            }
            p.executeBatch();
            c().commit();
        } catch (SQLException e) {
            c().rollback();
            throw e;
        } finally {
            c().setAutoCommit(ac);
        }
    }

    public synchronized void deleteUsBars(String symbol) throws SQLException {
        try (PreparedStatement p = prepare("DELETE FROM us_bars WHERE symbol=?")) { p.setString(1, symbol); p.executeUpdate(); }
    }

    public synchronized List<Bar> usBars(String symbol, String toDate) throws SQLException {
        List<Bar> l = new ArrayList<>();
        try (PreparedStatement p = prepare("SELECT date, open, high, low, close, volume FROM us_bars WHERE symbol=? AND date<=? ORDER BY date")) {
            p.setString(1, symbol); p.setString(2, toDate);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) l.add(Bar.of(LocalDate.parse(r.getString(1)), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getDouble(6)));
            }
        }
        return l;
    }

    public synchronized void upsertFlows(String code, List<FlowDay> fl) throws SQLException {
        boolean ac = c().getAutoCommit();
        c().setAutoCommit(false);
        try (PreparedStatement p = prepare("INSERT OR REPLACE INTO flows(code, date, foreign_qty, inst_qty, foreign_hold, close) VALUES(?,?,?,?,?,?)")) {
            for (FlowDay f : fl) {
                p.setString(1, code); p.setString(2, f.date().toString()); setD(p, 3, f.foreignQty()); setD(p, 4, f.instQty());
                setD(p, 5, f.foreignHoldPct()); p.setDouble(6, f.close());
                p.addBatch();
            }
            p.executeBatch();
            c().commit();
        } catch (SQLException e) {
            c().rollback();
            throw e;
        } finally {
            c().setAutoCommit(ac);
        }
    }

    public synchronized List<FlowDay> flows(String code, String toDate) throws SQLException {
        List<FlowDay> l = new ArrayList<>();
        try (PreparedStatement p = prepare("SELECT date, foreign_qty, inst_qty, foreign_hold, close FROM flows WHERE code=? AND date<=? ORDER BY date")) {
            p.setString(1, code); p.setString(2, toDate);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) l.add(new FlowDay(LocalDate.parse(r.getString(1)), getD(r, 2), getD(r, 3), null, getD(r, 4), r.getDouble(5)));
            }
        }
        return l;
    }

    private static Double getD(ResultSet r, int i) throws SQLException { double d = r.getDouble(i); return r.wasNull() ? null : d; }

    public synchronized String[] sector(String market, String symbol, int maxAgeDays) throws SQLException {
        try (PreparedStatement p = prepare("SELECT sector, sector_name, updated FROM sectors WHERE market=? AND symbol=?")) {
            p.setString(1, market); p.setString(2, symbol);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                if (LocalDateTime.parse(r.getString(3)).isBefore(LocalDateTime.now().minusDays(maxAgeDays))) return null;
                return new String[]{r.getString(1), r.getString(2)};
            }
        }
    }

    public synchronized void setSector(String market, String symbol, String sector, String name, String src) throws SQLException {
        try (PreparedStatement p = prepare("INSERT OR REPLACE INTO sectors(market, symbol, sector, sector_name, source, updated) VALUES(?,?,?,?,?,?)")) {
            p.setString(1, market); p.setString(2, symbol); p.setString(3, sector); p.setString(4, name); p.setString(5, src); p.setString(6, now());
            p.executeUpdate();
        }
    }

    // ---------- 설정 ----------
    public synchronized String setting(String k, String def) throws SQLException {
        try (PreparedStatement p = prepare("SELECT value FROM settings WHERE `key`=?")) {
            p.setString(1, k);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : def; }
        }
    }

    public synchronized void setSetting(String k, String v) throws SQLException {
        try (PreparedStatement p = prepare("INSERT OR REPLACE INTO settings(`key`, value) VALUES(?,?)")) {
            p.setString(1, k); p.setString(2, v); p.executeUpdate();
        }
    }

    // ---------- 범용 조회 ----------
    public synchronized List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement p = prepare(sql)) {
            for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
            try (ResultSet r = p.executeQuery()) {
                ResultSetMetaData md = r.getMetaData();
                while (r.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) m.put(md.getColumnLabel(i), r.getObject(i));
                    out.add(m);
                }
            }
        }
        return out;
    }

    public synchronized List<String> columns(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement p = prepare(sql); ResultSet r = p.executeQuery()) {
            ResultSetMetaData md = r.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) out.add(md.getColumnLabel(i));
        }
        return out;
    }

    public synchronized int count(String sql, Object... args) throws SQLException {
        List<Map<String, Object>> r = query(sql, args);
        if (r.isEmpty()) return 0;
        Object v = r.get(0).values().iterator().next();
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static String now() { return LocalDateTime.now().withNano(0).toString(); }
}
