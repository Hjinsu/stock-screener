package screener;

import screener.engine.Pipeline;
import screener.provider.DemoProvider;
import screener.store.Db;
import screener.store.Schema;
import screener.store.SqliteMigration;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 스키마·이관 테스트. 통합 테스트는 SCREENER_TEST_DB_URL(MariaDB)이 있을 때만 실행. */
final class MigrationTests {
    private MigrationTests() { }

    static String canon(Object v) throws Exception {
        Method m = SqliteMigration.class.getDeclaredMethod("canon", Object.class);
        m.setAccessible(true);
        return (String) m.invoke(null, v);
    }

    /** 체크섬 정규화가 금액 정밀도·타입 차이를 구분하는지. 방언 변환 규칙. */
    static void canonAndDialect() throws Exception {
        double a = 98079.4197886583;
        TestMain.check(!canon(a).equals(canon(Math.nextUp(a))), "1 ulp 차이 검출");
        TestMain.check(!canon(1).equals(canon(1.0)), "정수 1과 실수 1.0 구분");
        TestMain.eq(canon(1), canon(1L), "Integer/Long 동일 취급");
        TestMain.check(!canon("2026-11-12").equals(canon("2026-11-12 ")), "기준 시각 문자열 1자 차이 검출");
        TestMain.eq(Schema.dml("INSERT OR IGNORE INTO t(a) VALUES(?)", Schema.Dialect.MARIADB), "INSERT IGNORE INTO t(a) VALUES(?)", "INSERT OR IGNORE 변환");
        TestMain.eq(Schema.dml("INSERT INTO t(a,b) VALUES(?,?) ON CONFLICT(a) DO UPDATE SET b=excluded.b", Schema.Dialect.MARIADB),
                "INSERT INTO t(a,b) VALUES(?,?) ON DUPLICATE KEY UPDATE b=VALUES(b)", "UPSERT 변환");
        TestMain.check(Schema.ddl("CREATE TABLE x(id %ID%, k %K10%, v %R%)", Schema.Dialect.MARIADB).contains("VARCHAR(10)"), "키 컬럼 VARCHAR");
        TestMain.check(Schema.ddl("CREATE TABLE x(v %R%)", Schema.Dialect.MARIADB).contains("DOUBLE") && Schema.ddl("CREATE TABLE x(v %R%)", Schema.Dialect.SQLITE).contains("REAL"), "실수 타입");
    }

    /** 마이그레이션 이전 형식(스키마 기록 없음·end_as_of 없음) SQLite 를 열면 버전이 적용되고 컬럼이 추가되는지. */
    static void legacySqliteUpgrade() throws Exception {
        Path h = Files.createTempDirectory("legacy");
        Path f = h.resolve("old.db");
        Class.forName("org.sqlite.JDBC");
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + f); var st = c.createStatement()) {
            st.execute("CREATE TABLE tracking(id INTEGER PRIMARY KEY AUTOINCREMENT, model TEXT, market TEXT, symbol TEXT, name TEXT, detect_date TEXT, detect_close REAL,"
                    + " detect_score REAL, detect_state TEXT, bench TEXT, bench_close REAL, status TEXT, end_reason TEXT, invalid_reason TEXT, last_update TEXT, UNIQUE(model, market, symbol, detect_date))");
            st.execute("INSERT INTO tracking(model, market, symbol, detect_date, status) VALUES('MAS','KR','000001','2026-01-02','ACTIVE')");
        }
        try (Db db = new Db(f)) {
            TestMain.eq(db.schemaVersion(), Schema.latestVersion(), "최신 버전 적용");
            TestMain.check(db.hasColumn("tracking", "end_as_of"), "end_as_of 추가");
            TestMain.eq(db.count("SELECT COUNT(*) FROM tracking"), 1, "기존 행 보존");
        }
        try (Db db = new Db(f)) { TestMain.eq(db.migrate().size(), 0, "재실행 시 추가 적용 없음"); }
    }

    /** (MariaDB) 데모 이력 SQLite → MariaDB 이관 PASS, 재이관 거부, 원본 퇴역 표시. */
    static void sqliteToMariaDb() throws Exception {
        if (!TestDb.mariadb()) { System.out.println("      (건너뜀: SCREENER_TEST_DB_URL 미설정)"); return; }
        Path h = Files.createTempDirectory("mig");
        Config cfg = Config.forTest(h);
        Path src = h.resolve("src.db");
        try (Db s = new Db(src)) {
            DemoProvider p = new DemoProvider("US");
            List<java.time.LocalDate> cal = p.calendar();
            for (int i = DemoProvider.DAYS - 40; i < DemoProvider.DAYS; i++) { p.setAsOf(cal.get(i)); new Pipeline(p, s, cfg).run(); }
            TestMain.check(s.count("SELECT COUNT(*) FROM paper_equity_daily") > 0, "원본 모의 평가 존재");
        }
        try (Db target = TestDb.open(h.resolve("unused.db"))) {
            SqliteMigration.Result r = SqliteMigration.run(src, target, h, true);
            TestMain.check(r.passed(), "이관 PASS: " + r.report().get("problems"));
            TestMain.eq(target.setting("instance_role", ""), "active", "대상 active");
            TestMain.eq(target.setting("schedule_enabled", ""), "false", "대상 앱 내 스케줄러 끔");
            SqliteMigration.Result r2 = SqliteMigration.run(src, target, h, false);
            TestMain.check(!r2.passed(), "재이관 거부");
        }
        try (Db s = new Db(src)) {
            TestMain.eq(s.setting("instance_role", ""), "retired", "원본 퇴역 표시");
            boolean refused = false;
            try { Runner.assertActive(s); } catch (Runner.Locked e) { refused = true; }
            TestMain.check(refused, "퇴역 원본에서 실행 거부");
        }
        @SuppressWarnings("unused") Map<String, Object> none = Map.of();
    }
}
