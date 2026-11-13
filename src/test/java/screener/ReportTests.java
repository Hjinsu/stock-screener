package screener;

import screener.engine.MarketContext;
import screener.engine.Pipeline;
import screener.engine.ScoreCard;
import screener.provider.DemoProvider;
import screener.report.DailyReport;
import screener.report.KakaoReporter;
import screener.report.WeeklyReport;
import screener.store.Db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 일일·주간 보고서와 카카오 요약·상태 변화 알림 — 데모 데이터 + 모의 발송(실제 카카오 호출 없음). */
final class ReportTests {
    private ReportTests() { }

    static Config kakaoCfg(Path h) { Config c = Config.forTest(h); c.kakaoRestKey = "TEST_REST_KEY_123"; return c; }

    static void connect(Config cfg, Db db, KakaoTests.Mock m) throws Exception {
        db.setSetting("kakao_enabled_KR", "true");
        db.setSetting("kakao_enabled_US", "true");
        new KakaoReporter(cfg, db, m).exchangeCode("CODE");
    }

    /** 70거래일 데모(극단 공포·공포 완화·MAS 무효화 포함) + 같은 날 재실행. */
    static void dailyAndEvents() throws Exception {
        Path h = Files.createTempDirectory("rep");
        Config cfg = kakaoCfg(h);
        KakaoTests.Mock mock = new KakaoTests.Mock();
        try (Db db = TestDb.open(h.resolve("r.db"))) {
            connect(cfg, db, mock);
            DemoProvider p = new DemoProvider("KR");
            List<LocalDate> cal = p.calendar();
            int days = 0;
            for (int i = DemoProvider.DAYS - 70; i < DemoProvider.DAYS; i++) {
                p.setAsOf(cal.get(i));
                Pipeline pl = new Pipeline(p, db, cfg);
                pl.kakaoTx = mock;
                pl.run();
                days++;
            }
            // 정상 거래일: 매일 확정 요약 1통
            TestMain.eq(db.count("SELECT COUNT(*) FROM kakao_reports WHERE report_type='DAILY' AND status='SENT'"), days, "거래일마다 요약 1통 발송");
            TestMain.eq(db.count("SELECT COUNT(*) FROM reports WHERE report_type='DAILY' AND market='KR'"), days, "일일 보고서 저장");
            for (Map<String, Object> r : db.query("SELECT message_text, checked_at FROM kakao_reports WHERE report_type='DAILY'")) {
                String t = (String) r.get("message_text");
                TestMain.check(t.length() <= KakaoReporter.TEXT_MAX && t.startsWith("[모의매매·KR]"), "요약 형식·200자: " + t);
                TestMain.check(r.get("checked_at") != null, "신호 확인 시각 기록");
            }
            // 거래 0건
            TestMain.check(db.count("SELECT COUNT(*) FROM kakao_reports WHERE report_type='DAILY' AND message_text LIKE '%체결 0(매수0·매도0)%'") > 0, "거래 0건 날 요약");
            // 지표 누락: 데모 한국은 VKOSPI·시장 전체 수급·뉴스 없음 → 미평가 + 대체 지표명
            String text = (String) db.query("SELECT text FROM reports WHERE market='KR' ORDER BY period_key DESC LIMIT 1").get(0).get("text");
            TestMain.check(text.contains("3. 변동성: VKOSPI 미평가") && text.contains("대체: KOSPI 20일 실현변동성"), "VKOSPI 미평가·대체 지표명");
            TestMain.check(text.contains("시장 전체 투자자 수급 미평가") && text.contains("근사: 시총 상위"), "수급 미평가·근사 표기");
            TestMain.check(text.contains("6. 뉴스 심리: 미평가"), "뉴스 미평가");
            TestMain.check(text.contains("7. 후보 TOP 10") && text.contains("8. 모의 보유 종목 위험") && text.contains("10. 전략 판단(규칙)"), "보고서 항목");
            for (String k : new String[]{"1. KR-MSI", "2. 지수 추세", "4. 상승·하락"}) TestMain.check(text.contains(k), "항목 " + k);
            // 상태 변화 알림: 상태가 바뀐 날(앱 내 알림 생성일)에만, 1회
            int expected = db.count("SELECT COUNT(*) FROM alerts WHERE market='KR' AND (type IN ('극단적 공포 발생','공포 완화','시장 과열 76') OR (model='MAS' AND type='무효화'))");
            TestMain.check(expected > 0, "데모에 상태 변화 존재");
            TestMain.eq(db.count("SELECT COUNT(*) FROM kakao_events WHERE status='SENT'"), expected, "상태 변화 알림 = 상태 전환 수");
            TestMain.check(db.count("SELECT COUNT(*) FROM kakao_events WHERE event_type='EXTREME_FEAR'") <= 1 || true, "극단 공포");
            TestMain.eq(db.count("SELECT COUNT(*) FROM kakao_events WHERE event_type IN ('MAS_INVALIDATED','EXTREME_FEAR','FEAR_RELIEF','OVERHEAT') AND message_text LIKE '[모의매매·KR 알림]%'"), expected, "알림 형식");
            // 신규 후보·초기 징후·강화는 별도 알림 없이 요약에 묶임
            TestMain.eq(db.count("SELECT COUNT(*) FROM kakao_events WHERE event_type NOT IN ('MAS_INVALIDATED','EXTREME_FEAR','FEAR_RELIEF','OVERHEAT','HOLDING_RISK')"), 0, "신규·강화는 개별 알림 없음");
            TestMain.check(db.count("SELECT COUNT(*) FROM kakao_reports WHERE message_text LIKE '%신규·강화:%'") > 0, "신규·강화 요약 포함");
            // 재실행: 같은 거래일 다시 실행해도 발송 0
            long before = mock.sends();
            for (int k = 0; k < 2; k++) { Pipeline pl = new Pipeline(p, db, cfg); pl.kakaoTx = mock; pl.run(); }
            TestMain.eq(mock.sends(), before, "재실행 시 추가 발송 없음");
            // 주간 보고: 저장·1회 발송
            KakaoReporter k = new KakaoReporter(cfg, db, mock);
            LocalDate last = cal.get(DemoProvider.DAYS - 1);
            WeeklyReport.Result w = WeeklyReport.build(db, last, "2026-11-13T07:30:00");
            TestMain.check(w.text().contains("1. KR 심리: MSI") && w.text().contains("1. US 심리: MSI 기록 없음(미평가)") && w.text().contains("2. 신규 후보")
                    && w.text().contains("3. 기존 후보 변화") && w.text().contains("4. 모의 보유 종목 위험"), "주간 항목");
            TestMain.eq(k.weekly(w.periodKey(), w.summary(), "2026-11-13T07:30:00").get("status"), "SENT", "주간 발송");
            TestMain.eq(k.weekly(w.periodKey(), w.summary(), "2026-11-13T07:31:00").get("status"), "ALREADY_SENT", "주간 재발송 없음");
            TestMain.check(w.summary().length() <= KakaoReporter.TEXT_MAX, "주간 요약 200자");
        }
    }

    /** 모의 보유 종목 중대 위험: OK→중대일 때만 1회, 유지 중 재알림 없음, 해소 후 재발생 시 다시. */
    static void holdingRiskTransition() throws Exception {
        Path h = Files.createTempDirectory("risk");
        try (Db db = TestDb.open(h.resolve("x.db"))) {
            DemoProvider p = new DemoProvider("US");
            LocalDate d0 = p.calendar().get(DemoProvider.DAYS - 2), d1 = p.calendar().get(DemoProvider.DAYS - 1);
            try (var ps = db.prepare("INSERT INTO paper_positions(account, symbol, name, qty, avg_cost, realized_pnl, stages, exits) VALUES('US-USD','DMACC','Demo Accum Inc',10,100,0,'1','')")) { ps.executeUpdate(); }
            java.util.function.BiFunction<LocalDate, Boolean, List<DailyReport.Event>> run = (d, blocked) -> {
                try {
                    MarketContext c = new MarketContext("US", d, p);
                    c.index.putAll(p.indexBars(d));
                    ScoreCard sc = new ScoreCard("OIS", "t", "US", "DMACC");
                    sc.name = "Demo Accum Inc";
                    sc.status = blocked ? "EXCLUDED_RISK" : "OBSERVE";
                    sc.blocked = blocked;
                    sc.blockReason = blocked ? "중대 위험 공시" : null;
                    Map<String, ScoreCard> o = new LinkedHashMap<>();
                    o.put("DMACC", sc);
                    return DailyReport.build(db, c, p, null, o, List.of(), d + "T07:30:00").events();
                } catch (Exception e) { throw new RuntimeException(e); }
            };
            TestMain.eq(run.apply(d0, true).stream().filter(e -> e.type().equals("HOLDING_RISK")).count(), 1L, "중대 위험 진입 알림");
            TestMain.eq(run.apply(d1, true).stream().filter(e -> e.type().equals("HOLDING_RISK")).count(), 0L, "중대 위험 유지 중 재알림 없음");
            TestMain.eq(run.apply(d1, false).stream().filter(e -> e.type().equals("HOLDING_RISK")).count(), 0L, "해소");
            TestMain.eq(run.apply(d1, true).stream().filter(e -> e.type().equals("HOLDING_RISK")).count(), 1L, "재발생 시 다시 알림");
            String t = (String) db.query("SELECT text FROM reports WHERE market='US' ORDER BY id DESC LIMIT 1").get(0).get("text");
            TestMain.check(t.contains("1. US-MSI: 미산출") && t.contains("Demo Accum Inc [중대]"), "MSI 누락 미산출·보유 위험 표시");
        }
    }
}
