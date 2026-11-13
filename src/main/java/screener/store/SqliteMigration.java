package screener.store;

import screener.util.Json;
import screener.util.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SQLite → MariaDB 이관과 검증.
 * 1) 원본을 VACUUM INTO 로 일관 백업(SHA-256 기록) 2) 백업본을 읽기 전용으로 열어 원본 ID 그대로 복사(대상이 비어 있어야 함)
 * 3) 테이블별 건수·내용 체크섬(실수는 비트 단위)·통화별 잔액/손익·대표 주문-체결 관계·참조 무결성을 대조
 * 4) 모두 통과할 때만 전환 설정 적용(대상: 앱 내 스케줄러 끔·active 표시, 원본: --retire-source 시 retired 표시).
 */
public final class SqliteMigration {
    private SqliteMigration() { }

    public record Result(boolean passed, Path reportDir, Map<String, Object> report) { }

    public static Result run(Path source, Db target, Path workDir, boolean retireSource) throws Exception {
        if (target.dialect() != Schema.Dialect.MARIADB) throw new IllegalArgumentException("대상은 MariaDB 여야 합니다(SCREENER_DB_URL 설정 필요)");
        if (!Files.exists(source)) throw new IllegalArgumentException("원본 SQLite 없음: " + source);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path dir = workDir.resolve("migration").resolve(ts);
        Files.createDirectories(dir);
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("startedAt", LocalDateTime.now().withNano(0).toString());
        rep.put("source", source.toAbsolutePath().toString());
        rep.put("target", target.describe());
        List<String> problems = new ArrayList<>();

        // 1) 일관 백업
        Path backup = dir.resolve("source-backup.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection sc = java.sql.DriverManager.getConnection("jdbc:sqlite:" + source.toAbsolutePath()); Statement st = sc.createStatement()) {
            st.execute("VACUUM INTO '" + backup.toAbsolutePath().toString().replace("'", "''") + "'");
        }
        String backupSha = sha256(backup);
        rep.put("backup", backup.toAbsolutePath().toString());
        rep.put("backupSha256", backupSha);
        Files.writeString(dir.resolve("source-backup.db.sha256"), backupSha + "  source-backup.db\n");

        org.sqlite.SQLiteConfig ro = new org.sqlite.SQLiteConfig();
        ro.setReadOnly(true);
        try (Connection src = ro.createConnection("jdbc:sqlite:" + backup.toAbsolutePath())) {
            Set<String> srcTables = tables(src);
            rep.put("sourceSchemaVersion", srcTables.contains("schema_migrations") ? scalar(src, "SELECT MAX(version) FROM schema_migrations") : "기록 없음(마이그레이션 도입 이전 DB)");
            rep.put("targetSchemaVersion", target.schemaVersion());

            // 2) 대상 비어 있음 확인
            Connection tc = target.conn();
            Map<String, Long> pre = new LinkedHashMap<>();
            for (String t : Schema.TABLE_ORDER.keySet()) pre.put(t, count(tc, t));
            long nonEmpty = pre.values().stream().filter(v -> v > 0).count();
            if (nonEmpty > 0) {
                problems.add("대상 DB에 이미 데이터가 있음(중복 이관 방지): " + pre);
                return finish(dir, rep, problems, false);
            }

            // 3) 복사(참조 순서, 원본 ID 보존)
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : Schema.TABLE_ORDER.entrySet()) {
                String t = e.getKey();
                if (!srcTables.contains(t)) { copy.put(t, "원본에 테이블 없음 — 건너뜀(0건)"); continue; }
                List<String> sCols = columns(src, t), tCols = columns(tc, t);
                List<String> cols = new ArrayList<>();
                for (String c : sCols) if (tCols.stream().anyMatch(x -> x.equalsIgnoreCase(c))) cols.add(c);
                List<String> onlySrc = new ArrayList<>(sCols); onlySrc.removeIf(c -> tCols.stream().anyMatch(x -> x.equalsIgnoreCase(c)));
                List<String> onlyTgt = new ArrayList<>(tCols); onlyTgt.removeIf(c -> sCols.stream().anyMatch(x -> x.equalsIgnoreCase(c)));
                if (!onlySrc.isEmpty()) problems.add(t + ": 대상에 없는 원본 컬럼 " + onlySrc + " — 이관 시 손실");
                long n = copyTable(src, tc, t, cols);
                Map<String, Object> ci = new LinkedHashMap<>();
                ci.put("rows", n);
                if (!onlyTgt.isEmpty()) ci.put("targetOnlyColumns(NULL)", onlyTgt);
                copy.put(t, ci);
            }
            rep.put("copy", copy);

            // 4) 검증
            Map<String, Object> counts = new LinkedHashMap<>(), sums = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : Schema.TABLE_ORDER.entrySet()) {
                String t = e.getKey();
                if (!srcTables.contains(t)) continue;
                long a = count(src, t), b = count(tc, t);
                counts.put(t, a + " / " + b + (a == b ? " 일치" : " 불일치"));
                if (a != b) problems.add(t + " 건수 불일치 " + a + " vs " + b);
                List<String> cols = columns(src, t);
                List<String> tc2 = columns(tc, t);
                cols.removeIf(c -> tc2.stream().noneMatch(x -> x.equalsIgnoreCase(c)));
                String hs = checksum(src, t, cols, e.getValue()), ht = checksum(tc, t, cols, e.getValue());
                sums.put(t, hs.substring(0, 16) + (hs.equals(ht) ? " 일치" : " 불일치(대상 " + ht.substring(0, 16) + ")"));
                if (!hs.equals(ht)) problems.add(t + " 내용 체크섬 불일치(값·정밀도·기준 시각 차이)");
            }
            rep.put("counts(원본 / 대상)", counts);
            rep.put("checksums(SHA-256 앞 16자)", sums);

            if (srcTables.contains("paper_accounts")) {
                Map<String, Object> money = new LinkedHashMap<>();
                compare(money, problems, "통화별 현금 잔액", src, tc, "SELECT currency, SUM(cash) FROM paper_accounts GROUP BY currency ORDER BY currency");
                compare(money, problems, "계좌별 실현손익 합계", src, tc, "SELECT account, SUM(realized_pnl), COUNT(*) FROM paper_fills GROUP BY account ORDER BY account");
                compare(money, problems, "계좌별 최신 평가액", src, tc, "SELECT e.account, e.date, e.equity, e.cash, e.realized_cum, e.unrealized, e.currency FROM paper_equity_daily e"
                        + " WHERE e.date=(SELECT MAX(x.date) FROM paper_equity_daily x WHERE x.account=e.account) ORDER BY e.account");
                compare(money, problems, "계좌별 입출금 합계", src, tc, "SELECT account, SUM(amount), COUNT(*) FROM paper_cashflows GROUP BY account ORDER BY account");
                rep.put("money", money);
                Map<String, Object> rel = new LinkedHashMap<>();
                List<Long> ids = sampleOrderIds(src);
                rel.put("sampleOrderIds", ids);
                if (!ids.isEmpty()) {
                    String in = ids.toString().replace("[", "(").replace("]", ")");
                    compare(rel, problems, "대표 신호→주문→체결 관계", src, tc,
                            "SELECT o.id, o.signal_id, s.signal_key, o.status, o.side, o.target_date, f.id, f.fill_date, f.qty, f.fill_price, f.net_cash, f.realized_pnl"
                                    + " FROM paper_orders o JOIN paper_signals s ON s.id=o.signal_id LEFT JOIN paper_fills f ON f.order_id=o.id WHERE o.id IN " + in + " ORDER BY o.id");
                }
                rep.put("relations", rel);
            }
            Map<String, Object> integrity = new LinkedHashMap<>();
            String[][] orphan = {
                {"체결→주문 누락", "SELECT COUNT(*) FROM paper_fills f LEFT JOIN paper_orders o ON o.id=f.order_id WHERE o.id IS NULL"},
                {"체결·주문 신호 ID 불일치", "SELECT COUNT(*) FROM paper_fills f JOIN paper_orders o ON o.id=f.order_id WHERE f.signal_id<>o.signal_id"},
                {"주문→신호 누락", "SELECT COUNT(*) FROM paper_orders o LEFT JOIN paper_signals s ON s.id=o.signal_id WHERE s.id IS NULL"},
                {"체크포인트→추적 누락", "SELECT COUNT(*) FROM checkpoints c LEFT JOIN tracking t ON t.id=c.tracking_id WHERE t.id IS NULL"},
            };
            for (String[] o : orphan) {
                long v = ((Number) scalar(tc, o[1])).longValue();
                integrity.put(o[0], v);
                if (v != 0) problems.add("대상 참조 무결성: " + o[0] + " " + v + "건");
            }
            rep.put("integrity(대상)", integrity);
            rep.put("reportHistory", "카카오 보고 발송 이력 테이블 없음(9항 미구현) — 이관 대상 없음");
            Map<String, Object> span = new LinkedHashMap<>();
            if (srcTables.contains("runs")) span.put("runs.started_at 범위", scalar(tc, "SELECT CONCAT(MIN(started_at), ' ~ ', MAX(started_at)) FROM runs"));
            if (srcTables.contains("scores")) span.put("scores.as_of 범위", scalar(tc, "SELECT CONCAT(MIN(as_of), ' ~ ', MAX(as_of)) FROM scores"));
            rep.put("timeSpan(대상)", span);
        }
        boolean passed = problems.isEmpty();
        if (passed) {
            target.setSetting("schedule_enabled", "false"); // 서버는 systemd 타이머로 실행 — 앱 내 스케줄러와 이중 실행 방지
            target.setSetting("instance_role", "active");
            target.setSetting("migrated_from_sha256", backupSha);
            target.setSetting("migrated_at", LocalDateTime.now().withNano(0).toString());
            if (retireSource) {
                try (Db s = new Db(source)) {
                    s.setSetting("instance_role", "retired");
                    s.setSetting("retired_at", LocalDateTime.now().withNano(0).toString());
                    s.setSetting("retired_reason", "MariaDB 이관 완료(" + target.describe() + ") — 이 PC에서 실행·예약·보고 중지");
                    s.setSetting("schedule_enabled", "false");
                }
                rep.put("sourceRetired", true);
            } else rep.put("sourceRetired", "아니오(--retire-source 미지정) — PC 실행을 직접 중지해야 함");
        }
        return finish(dir, rep, problems, passed);
    }

    private static Result finish(Path dir, Map<String, Object> rep, List<String> problems, boolean passed) throws Exception {
        rep.put("problems", problems);
        rep.put("result", passed ? "PASS" : "FAIL");
        rep.put("finishedAt", LocalDateTime.now().withNano(0).toString());
        Files.writeString(dir.resolve("report.json"), Json.write(rep), StandardCharsets.UTF_8);
        StringBuilder md = new StringBuilder("# SQLite → MariaDB 이관 보고서\n\n결과: **" + rep.get("result") + "**\n\n");
        for (Map.Entry<String, Object> e : rep.entrySet()) {
            if (e.getValue() instanceof Map) {
                md.append("## ").append(e.getKey()).append("\n\n| 항목 | 값 |\n|---|---|\n");
                for (Map.Entry<?, ?> x : ((Map<?, ?>) e.getValue()).entrySet()) md.append("| ").append(x.getKey()).append(" | ").append(String.valueOf(Json.write(x.getValue())).replace("|", "\\|")).append(" |\n");
                md.append("\n");
            } else md.append("- ").append(e.getKey()).append(": ").append(Json.write(e.getValue())).append("\n");
        }
        Files.writeString(dir.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);
        return new Result(passed, dir, rep);
    }

    // ---------- 도우미 ----------
    static Set<String> tables(Connection c) throws SQLException {
        Set<String> s = new LinkedHashSet<>();
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) { while (r.next()) s.add(r.getString(1)); }
        return s;
    }

    static List<String> columns(Connection c, String t) throws SQLException {
        List<String> l = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT * FROM " + t + " WHERE 1=0")) {
            ResultSetMetaData md = r.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) l.add(md.getColumnName(i));
        }
        return l;
    }

    static boolean hasCol(Connection c, String t, String col) throws SQLException {
        for (String x : columns(c, t)) if (x.equalsIgnoreCase(col)) return true;
        return false;
    }

    static long count(Connection c, String t) throws SQLException { return ((Number) scalar(c, "SELECT COUNT(*) FROM " + t)).longValue(); }

    static Object scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(sql)) { return r.next() ? r.getObject(1) : null; }
    }

    static String q(String col) { return "`" + col + "`"; }

    static long copyTable(Connection src, Connection tc, String t, List<String> cols) throws SQLException {
        String colList = String.join(", ", cols.stream().map(SqliteMigration::q).toList());
        String ph = String.join(",", cols.stream().map(x -> "?").toList());
        long n = 0;
        boolean ac = tc.getAutoCommit();
        tc.setAutoCommit(false);
        try (Statement st = src.createStatement(); ResultSet r = st.executeQuery("SELECT " + colList + " FROM " + t);
             PreparedStatement ps = tc.prepareStatement("INSERT INTO " + t + "(" + colList + ") VALUES(" + ph + ")")) {
            int batch = 0;
            while (r.next()) {
                for (int i = 1; i <= cols.size(); i++) ps.setObject(i, r.getObject(i));
                ps.addBatch();
                n++;
                if (++batch == 1000) { ps.executeBatch(); batch = 0; }
            }
            if (batch > 0) ps.executeBatch();
            tc.commit();
        } catch (SQLException e) {
            tc.rollback();
            throw new SQLException(t + " 복사 실패: " + e.getMessage(), e);
        } finally {
            tc.setAutoCommit(ac);
        }
        return n;
    }

    /** 정렬 키 순서로 모든 행을 정규화해 SHA-256. 정수는 값, 실수는 IEEE 754 비트, 문자열은 길이+내용으로 표현(정밀도·기준 시각 문자열 비교). */
    static String checksum(Connection c, String t, List<String> cols, String orderBy) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String colList = String.join(", ", cols.stream().map(SqliteMigration::q).toList());
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT " + colList + " FROM " + t + " ORDER BY " + orderBy)) {
            while (r.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= cols.size(); i++) b.append(canon(r.getObject(i))).append('\u0001');
                md.update(b.append('\n').toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    static String canon(Object v) {
        if (v == null) return "N";
        if (v instanceof Double || v instanceof Float) return "D" + Long.toHexString(Double.doubleToLongBits(((Number) v).doubleValue()));
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte || v instanceof java.math.BigInteger) return "I" + ((Number) v).longValue();
        if (v instanceof java.math.BigDecimal) return "M" + ((java.math.BigDecimal) v).toPlainString();
        String s = v.toString();
        return "S" + s.length() + ":" + s;
    }

    static List<List<String>> rows(Connection c, String sql) throws SQLException {
        List<List<String>> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(sql)) {
            int n = r.getMetaData().getColumnCount();
            while (r.next()) { List<String> row = new ArrayList<>(); for (int i = 1; i <= n; i++) row.add(canon(r.getObject(i))); out.add(row); }
        }
        return out;
    }

    static void compare(Map<String, Object> into, List<String> problems, String name, Connection a, Connection b, String sql) throws SQLException {
        List<List<String>> x = rows(a, sql), y = rows(b, sql);
        boolean eq = Objects.equals(x, y);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", eq ? "일치" : "불일치");
        m.put("rows", x.size());
        m.put("source", pretty(x));
        if (!eq) { m.put("target", pretty(y)); problems.add(name + " 불일치"); }
        into.put(name, m);
    }

    static List<String> pretty(List<List<String>> rows) {
        List<String> out = new ArrayList<>();
        for (List<String> r : rows) {
            List<String> v = new ArrayList<>();
            for (String c : r) {
                if (c.startsWith("D")) v.add(String.valueOf(Double.longBitsToDouble(Long.parseUnsignedLong(c.substring(1), 16))));
                else if (c.startsWith("S")) v.add(c.substring(c.indexOf(':') + 1));
                else if (c.equals("N")) v.add("null");
                else v.add(c.substring(1));
            }
            out.add(String.join(" | ", v));
            if (out.size() >= 10) { out.add("…(" + rows.size() + "행)"); break; }
        }
        return out;
    }

    /** 대표 주문: 최소·최대 ID와 체결 있는 주문 중 균등 간격 3건. */
    static List<Long> sampleOrderIds(Connection src) throws SQLException {
        List<Long> all = new ArrayList<>();
        try (Statement st = src.createStatement(); ResultSet r = st.executeQuery("SELECT id FROM paper_orders ORDER BY id")) { while (r.next()) all.add(r.getLong(1)); }
        List<Long> filled = new ArrayList<>();
        try (Statement st = src.createStatement(); ResultSet r = st.executeQuery("SELECT order_id FROM paper_fills ORDER BY order_id")) { while (r.next()) filled.add(r.getLong(1)); }
        LinkedHashSet<Long> s = new LinkedHashSet<>();
        if (!all.isEmpty()) { s.add(all.get(0)); s.add(all.get(all.size() - 1)); }
        for (int k = 1; k <= 3 && !filled.isEmpty(); k++) s.add(filled.get(Math.min(filled.size() - 1, filled.size() * k / 4)));
        return new ArrayList<>(s);
    }

    public static String sha256Of(Path p) throws Exception { return sha256(p); }

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static void logResult(Result r) {
        Log.info("이관 결과 " + (r.passed() ? "PASS" : "FAIL") + " — 보고서 " + r.reportDir().resolve("report.md"));
    }
}
