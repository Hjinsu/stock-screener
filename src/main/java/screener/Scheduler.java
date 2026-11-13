package screener;

import screener.store.Db;
import screener.util.Log;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 대시보드(serve) 실행 중 장 마감 후 자동 실행. 시각은 한국시간(KST) 기준으로 설정한다.
 * - KR: 평일 지정 시각(기본 18:30) — KRX 자료가 아직 없으면 직전 영업일이 기준일로 기록됨
 * - US: 화~토 지정 시각(기본 07:00 KST, 미국 장 마감 후)
 * 프로세스 밖 자동 실행은 scripts\register-task.ps1(Windows 작업 스케줄러)을 사용한다.
 */
public final class Scheduler implements Runnable {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Config cfg;
    private final Db db;
    private volatile boolean stop;

    public Scheduler(Config cfg, Db db) { this.cfg = cfg; this.db = db; }

    public void stop() { stop = true; }

    @Override public void run() {
        while (!stop) {
            try {
                tick(LocalDateTime.now(KST));
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.warn("스케줄러 오류: " + e.getMessage());
            }
        }
    }

    void tick(LocalDateTime now) throws Exception {
        if (!"true".equals(db.setting("schedule_enabled", "false"))) return;
        if ("retired".equals(db.setting("instance_role", ""))) return;
        String hm = String.format("%02d:%02d", now.getHour(), now.getMinute());
        DayOfWeek dw = now.getDayOfWeek();
        String today = now.toLocalDate().toString();
        if (hm.compareTo(db.setting("schedule_kr", "18:30")) >= 0 && dw.getValue() <= 5 && !today.equals(db.setting("schedule_last_kr", ""))) {
            db.setSetting("schedule_last_kr", today);
            launch("KR");
        }
        if (hm.compareTo(db.setting("schedule_us", "07:00")) >= 0 && dw.getValue() >= 2 && dw.getValue() <= 6 && !today.equals(db.setting("schedule_last_us", ""))) {
            db.setSetting("schedule_last_us", today);
            launch("US");
        }
    }

    private void launch(String market) {
        Thread t = new Thread(() -> {
            try {
                Log.info("예약 실행 시작: " + market);
                Runner.runLive(cfg, db, List.of(market));
                db.addAlert("SYSTEM", market, "-", "예약 실행", LocalDateTime.now(KST).toLocalDate().toString(), "예약 실행 완료",
                        "[시스템] " + market + " 예약 실행 완료 — 실행 내역에서 상태 확인", java.util.Map.of());
            } catch (Runner.Locked e) {
                Log.warn("예약 실행 건너뜀(" + market + "): " + e.getMessage());
            } catch (Exception e) {
                Log.error("예약 실행 실패(" + market + "): " + Log.redact(e.getMessage()));
            }
        }, "scheduled-" + market);
        t.setDaemon(true);
        t.start();
    }
}
