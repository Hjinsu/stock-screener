package screener;

import screener.engine.Pipeline;
import screener.provider.DemoProvider;
import screener.provider.KrLiveProvider;
import screener.provider.MarketDataProvider;
import screener.provider.UsLiveProvider;
import screener.store.Db;
import screener.util.Http;
import screener.util.Log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 실행 오케스트레이션 + 중복 실행 방지(프로세스 간 파일 잠금 + 프로세스 내 플래그). */
public final class Runner {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private Runner() { }

    public static boolean isRunning() { return RUNNING.get(); }

    public static final class Locked extends Exception {
        public Locked(String m) { super(m); }
    }

    private static <T> T withLock(Config cfg, String name, java.util.concurrent.Callable<T> body) throws Exception {
        if (!RUNNING.compareAndSet(false, true)) throw new Locked("이 프로세스에서 이미 실행 중");
        Files.createDirectories(cfg.home);
        Path lp = cfg.home.resolve(name);
        try (RandomAccessFile raf = new RandomAccessFile(lp.toFile(), "rw"); FileChannel ch = raf.getChannel()) {
            FileLock lock;
            try { lock = ch.tryLock(); } catch (IOException e) { lock = null; }
            if (lock == null) throw new Locked("다른 프로세스가 실행 중(잠금 파일 " + lp + ")");
            try { return body.call(); } finally { lock.release(); }
        } finally {
            RUNNING.set(false);
        }
    }

    /** 실데이터 실행. markets: KR, US. */
    /** 이관 후 퇴역 처리된 DB에서는 실행하지 않는다(PC·서버 이중 처리 방지). */
    public static void assertActive(Db db) throws Exception {
        if ("retired".equals(db.setting("instance_role", ""))) {
            throw new Locked("이 DB는 퇴역 처리됨(" + db.setting("retired_reason", "") + ", " + db.setting("retired_at", "") + ") — 이 환경에서는 실행하지 않습니다");
        }
    }

    public static List<Map<String, Object>> runLive(Config cfg, Db db, List<String> markets) throws Exception {
        assertActive(db);
        return withLock(cfg, "run.lock", () -> {
            db.markStaleRuns();
            Http http = new Http(cfg.cacheDir, cfg.userAgent);
            List<Map<String, Object>> out = new ArrayList<>();
            for (String m : markets) {
                MarketDataProvider p = "KR".equals(m) ? new KrLiveProvider(cfg, http, db) : new UsLiveProvider(cfg, http, db);
                try {
                    out.add(new Pipeline(p, db, cfg).run());
                } catch (Exception e) {
                    // 시장 단위 격리: 한 시장 실패가 다른 시장 실행을 막지 않는다
                    Log.error("[" + m + "] 처리 중 예외: " + Log.redact(String.valueOf(e)));
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("market", m);
                    f.put("status", "FAILED");
                    f.put("error", Log.redact(String.valueOf(e)));
                    out.add(f);
                }
            }
            return out;
        });
    }

    /** 데모 실행: 합성 달력의 최근 days 거래일을 하루씩 순차 실행(추적·상태 전환 시연). 실데이터 DB와 분리. */
    public static List<Map<String, Object>> runDemo(Config cfg, Db db, int days) throws Exception {
        return withLock(cfg, "demo.lock", () -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String m : new String[]{"KR", "US"}) {
                DemoProvider p = new DemoProvider(m);
                List<LocalDate> cal = p.calendar();
                for (int i = Math.max(0, cal.size() - days); i < cal.size(); i++) {
                    p.setAsOf(cal.get(i));
                    Map<String, Object> r = new Pipeline(p, db, cfg).run();
                    if (i == cal.size() - 1) out.add(r);
                }
            }
            return out;
        });
    }
}
