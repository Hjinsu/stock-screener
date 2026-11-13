package screener;

import screener.store.Db;
import screener.util.Json;
import screener.util.Log;
import screener.web.DashboardServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 한국·미국 주식 자동 선별 (분석·모의 검증 전용 — 주문 기능 없음).
 *
 * 사용:
 *   run  [--market KR|US|ALL] [--limit N]   실데이터: 상장목록 자동 수집 → 선별 → 저장
 *   demo [--days 70]                       합성 데이터 데모(별도 DB data/demo.db)
 *   serve [--demo] [--port 8765]           로컬 대시보드(127.0.0.1) + 장 마감 후 자동 실행
 *   check-sources                          공급원별 소량 요청 점검
 */
public final class App {
    public static void main(String[] args) throws Exception {
        String cmd = args.length == 0 ? "help" : args[0];
        String market = opt(args, "--market", "ALL").toUpperCase();
        int limit = Integer.parseInt(opt(args, "--limit", "0"));
        boolean demo = "demo".equals(cmd) || has(args, "--demo");
        Config cfg = Config.load(demo, limit);
        Log.init(cfg.logDir);
        switch (cmd) {
            case "run": {
                Log.info("설정: " + cfg.keyStatus() + ", 데이터 경로 " + cfg.home);
                List<String> ms = new ArrayList<>();
                if (market.equals("ALL") || market.equals("KR")) ms.add("KR");
                if (market.equals("ALL") || market.equals("US")) ms.add("US");
                try (Db db = Db.open(cfg)) {
                    Log.info("DB: " + db.describe() + ", 스키마 v" + db.schemaVersion());
                    List<Map<String, Object>> r = Runner.runLive(cfg, db, ms);
                    printSummary(r);
                    boolean failed = r.stream().anyMatch(x -> "FAILED".equals(x.get("status")));
                    System.exit(failed ? 2 : 0);
                } catch (Runner.Locked e) {
                    Log.error("실행 잠금: " + e.getMessage());
                    System.exit(3);
                }
                break;
            }
            case "demo": {
                int days = Integer.parseInt(opt(args, "--days", "70"));
                try (Db db = Db.open(cfg)) {
                    Log.info("데모 실행(합성 데이터, 실제 시세 아님) — DB: " + cfg.dbPath);
                    printSummary(Runner.runDemo(cfg, db, days));
                }
                break;
            }
            case "serve": {
                int port = Integer.parseInt(opt(args, "--port", String.valueOf(cfg.port)));
                Db db = Db.open(cfg);
                db.markStaleRuns();
                DashboardServer srv = new DashboardServer(cfg, db, port);
                srv.start();
                // 서버(systemd 타이머 사용)에서는 SCREENER_INAPP_SCHEDULER=off 로 앱 내 스케줄러를 끈다(이중 실행 방지)
                if (!cfg.demo && !"off".equalsIgnoreCase(Config.env("SCREENER_INAPP_SCHEDULER"))) {
                    Thread t = new Thread(new Scheduler(cfg, db), "scheduler");
                    t.setDaemon(true);
                    t.start();
                }
                System.out.println("대시보드: http://127.0.0.1:" + port + "/  (" + (cfg.demo ? "데모 DB" : "실데이터 DB") + ": " + db.describe() + ")  종료: Ctrl+C");
                Thread.currentThread().join();
                break;
            }
            case "db-migrate": {
                try (Db db = Db.open(cfg)) {
                    System.out.println("DB: " + db.describe());
                    System.out.println("스키마 버전: " + db.schemaVersion() + " (최신 " + screener.store.Schema.latestVersion() + ") — 미적용 버전은 연결 시 자동 적용");
                }
                break;
            }
            case "db-status": {
                try (Db db = Db.open(cfg)) {
                    System.out.println("DB: " + db.describe());
                    System.out.println("스키마 버전: " + db.schemaVersion() + " / 최신 " + screener.store.Schema.latestVersion());
                    System.out.println("인스턴스 역할: " + db.setting("instance_role", "(미지정)") + (db.setting("retired_at", null) != null ? " (퇴역 " + db.setting("retired_at", "") + ")" : ""));
                    System.out.println("앱 내 스케줄러: " + db.setting("schedule_enabled", "false"));
                    for (String t : screener.store.Schema.TABLE_ORDER.keySet()) System.out.printf("  %-20s %d%n", t, db.count("SELECT COUNT(*) FROM " + t));
                    for (Map<String, Object> r : db.query("SELECT market, status, as_of, finished_at FROM runs WHERE id IN (SELECT MAX(id) FROM runs GROUP BY market)"))
                        System.out.println("  최근 실행 " + r.get("market") + ": " + r.get("status") + " 기준일 " + r.get("as_of") + " 종료 " + r.get("finished_at"));
                    for (Map<String, Object> r : db.query("SELECT account, currency, cash FROM paper_accounts ORDER BY account"))
                        System.out.println("  모의 계좌 " + r.get("account") + " 현금 " + r.get("cash") + " " + r.get("currency"));
                }
                break;
            }
            case "sqlite-snapshot": {
                // PC: 실행 중 쓰기 로그(-wal)까지 포함한 일관 사본(서버 이관용). 원본은 변경하지 않음.
                String out = opt(args, "--out", null);
                if (out == null || cfg.dbUrl != null) { System.out.println("사용: sqlite-snapshot --out <파일> (SQLite 모드에서만, SCREENER_DB_URL 미설정)"); System.exit(1); }
                java.nio.file.Path o = java.nio.file.Paths.get(out).toAbsolutePath();
                if (java.nio.file.Files.exists(o)) { System.out.println("이미 존재: " + o); System.exit(1); }
                Class.forName("org.sqlite.JDBC");
                try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + cfg.dbPath.toAbsolutePath()); java.sql.Statement st = c.createStatement()) {
                    st.execute("VACUUM INTO '" + o.toString().replace("'", "''") + "'");
                }
                System.out.println("스냅샷: " + o + "\nSHA-256: " + screener.store.SqliteMigration.sha256Of(o));
                break;
            }
            case "retire-local": {
                // PC: 서버 이관 PASS 후 로컬 SQLite 를 퇴역 처리(실행·예약 거부). --undo 로 복귀.
                if (cfg.dbUrl != null) { System.out.println("SQLite(로컬) 모드에서만 사용합니다(SCREENER_DB_URL 미설정 상태)."); System.exit(1); }
                try (Db db = Db.open(cfg)) {
                    if (has(args, "--undo")) {
                        db.setSetting("instance_role", "active");
                        db.setSetting("retired_reason", "");
                        System.out.println("로컬 DB 를 다시 활성화했습니다. 서버의 실행 타이머가 꺼져 있는지 반드시 확인하세요(이중 실행 방지).");
                    } else {
                        db.setSetting("instance_role", "retired");
                        db.setSetting("retired_at", java.time.LocalDateTime.now().withNano(0).toString());
                        db.setSetting("retired_reason", opt(args, "--reason", "서버(MariaDB)로 이관 완료 — PC 실행·예약·보고 중지"));
                        db.setSetting("schedule_enabled", "false");
                        System.out.println("로컬 DB 를 퇴역 처리했습니다: " + db.describe() + "\n이제 이 PC 에서 run·예약 실행은 거부됩니다. 작업 스케줄러도 해제하세요: scripts\\register-task.ps1 -Remove");
                    }
                }
                break;
            }
            case "migrate-sqlite": {
                String from = opt(args, "--from", null);
                if (from == null) { System.out.println("사용: migrate-sqlite --from <SQLite 파일> [--retire-source]"); System.exit(1); }
                if (cfg.dbUrl == null) { System.out.println("SCREENER_DB_URL(MariaDB)이 설정되지 않았습니다."); System.exit(1); }
                try (Db target = Db.open(cfg)) {
                    screener.store.SqliteMigration.Result r = screener.store.SqliteMigration.run(java.nio.file.Paths.get(from), target, cfg.home, has(args, "--retire-source"));
                    screener.store.SqliteMigration.logResult(r);
                    System.out.println("결과: " + (r.passed() ? "PASS" : "FAIL") + "\n보고서: " + r.reportDir().resolve("report.md"));
                    for (Object pr : (List<?>) r.report().get("problems")) System.out.println("  문제: " + pr);
                    System.exit(r.passed() ? 0 : 1);
                }
                break;
            }
            case "export-csv": {
                java.nio.file.Path out = java.nio.file.Paths.get(opt(args, "--out", cfg.home.resolve("export").resolve(java.time.LocalDate.now().toString()).toString()));
                try (Db db = Db.open(cfg)) {
                    for (java.nio.file.Path f : screener.engine.PaperExport.exportAll(db, out)) System.out.println("저장: " + f);
                }
                break;
            }
            case "check-sources": {
                System.out.println("키 설정 상태: " + cfg.keyStatus());
                for (Map<String, Object> r : SourceCheck.run(cfg)) {
                    System.out.printf("%-34s %-6s %s%s%n", r.get("source"), r.get("status"), r.get("detail"), r.get("ms") == null ? "" : " (" + r.get("ms") + "ms)");
                }
                break;
            }
            default:
                System.out.println("사용법:\n  run  [--market KR|US|ALL] [--limit N]\n  demo [--days 70]\n  serve [--demo] [--port 8765]\n  export-csv [--demo] [--out 폴더]\n  db-status | db-migrate\n  migrate-sqlite --from <SQLite 파일> [--retire-source]\n  sqlite-snapshot --out <파일>  |  retire-local [--undo]\n  check-sources");
        }
    }

    @SuppressWarnings("unchecked")
    static void printSummary(List<Map<String, Object>> rs) {
        for (Map<String, Object> r : rs) {
            System.out.println("──────── 실행 요약 ────────");
            System.out.println("상태: " + r.get("status") + (r.get("error") != null ? " / " + r.get("error") : ""));
            for (String k : new String[]{"completedDay", "listingsTotal", "commonStocks", "typeExcluded", "mcapUnknown", "scanRequested", "barsOk", "barsFailed",
                    "masFilter", "masScored", "masStatus", "oisUniverse", "oisScored", "oisStatus", "flowsOk", "fundamentalsOk", "sectorsOk",
                    "disclosuresOk", "disclosuresFailed", "msi", "newAlerts", "checkpointsAdded", "paper", "limitApplied"}) {
                if (r.containsKey(k)) System.out.println("  " + k + ": " + Json.write(r.get(k)));
            }
            Object n = r.get("notes");
            if (n instanceof List && !((List<Object>) n).isEmpty()) for (Object x : (List<Object>) n) System.out.println("  · " + x);
        }
    }

    static String opt(String[] a, String k, String def) {
        for (int i = 0; i < a.length - 1; i++) if (a[i].equals(k)) return a[i + 1];
        return def;
    }

    static boolean has(String[] a, String k) {
        for (String x : a) if (x.equals(k)) return true;
        return false;
    }
}
