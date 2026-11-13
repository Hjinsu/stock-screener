package screener;

import screener.store.Db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 DB. 기본은 임시 SQLite 파일. 환경 변수 SCREENER_TEST_DB_URL(예: jdbc:mariadb://127.0.0.1:3307/)과
 * SCREENER_TEST_DB_USER/SCREENER_TEST_DB_PASSWORD 가 있으면 테스트마다 새 MariaDB 데이터베이스(screener_t_*)를 만들어 같은 테스트를 실행한다.
 */
final class TestDb {
    private TestDb() { }

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000, 36);

    static boolean mariadb() { return System.getenv("SCREENER_TEST_DB_URL") != null; }

    static Db open(Path sqliteFile) throws Exception {
        if (!mariadb()) return new Db(sqliteFile);
        String base = System.getenv("SCREENER_TEST_DB_URL");
        if (!base.endsWith("/")) base += "/";
        String user = System.getenv("SCREENER_TEST_DB_USER"), pass = System.getenv("SCREENER_TEST_DB_PASSWORD");
        String name = "screener_t_" + RUN + "_" + SEQ.incrementAndGet();
        try (Connection c = DriverManager.getConnection(base, user, pass); Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        return new Db(base + name, user, pass);
    }

    /** 테스트가 만든 MariaDB 데이터베이스 정리. */
    static void cleanup() {
        if (!mariadb()) return;
        String base = System.getenv("SCREENER_TEST_DB_URL");
        try (Connection c = DriverManager.getConnection(base, System.getenv("SCREENER_TEST_DB_USER"), System.getenv("SCREENER_TEST_DB_PASSWORD"));
             Statement st = c.createStatement()) {
            for (int i = 1; i <= SEQ.get(); i++) st.execute("DROP DATABASE IF EXISTS screener_t_" + RUN + "_" + i);
        } catch (Exception e) {
            System.out.println("테스트 DB 정리 실패: " + e.getMessage());
        }
    }
}
