package screener;

import screener.engine.MarketContext;
import screener.engine.PaperExport;
import screener.engine.PaperTrader;
import screener.engine.ScoreCard;
import screener.engine.ScoreItem;
import screener.model.Bar;
import screener.model.Security;
import screener.provider.DemoProvider;
import screener.provider.KrLiveProvider;
import screener.store.Db;
import screener.util.Http;
import screener.util.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 모의 자동매매 오프라인 테스트(네트워크 없음). */
final class PaperTests {
    private PaperTests() { }

    static final LocalDate D0 = LocalDate.of(2026, 10, 1);

    /** 30거래일 달력: 종가 100 고정, 마지막 날 이후 바는 extra로 추가. */
    static List<LocalDate> days(int n) {
        List<LocalDate> l = new ArrayList<>();
        LocalDate d = D0;
        while (l.size() < n) { if (d.getDayOfWeek().getValue() <= 5) l.add(d); d = d.plusDays(1); }
        return l;
    }

    static List<Bar> flat(List<LocalDate> ds, double px) {
        List<Bar> b = new ArrayList<>();
        for (LocalDate d : ds) b.add(new Bar(d, px, px * 1.01, px * 0.99, px, 1_000_000, null, true, null));
        return b;
    }

    static ScoreCard card(String market, String sym, boolean mandatory, Boolean stage1Met) {
        ScoreCard c = new ScoreCard("OIS", "test", market, sym);
        c.name = "테스트" + sym;
        c.asOf = "x";
        ScoreItem it = new ScoreItem("재무·경쟁력", "health", "h", 100, "t");
        it.grade(0.7, "obs");
        c.add(it);
        c.status = "INTEREST";
        c.statusLabel = "관심종목";
        c.mandatoryPassed = mandatory;
        List<Map<String, Object>> sc = new ArrayList<>();
        for (int k = 1; k <= 4; k++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stage", k); m.put("condition", "조건" + k); m.put("met", k == 1 ? stage1Met : Boolean.FALSE); m.put("observed", "관측" + k);
            sc.add(m);
        }
        c.info.put("scenario", sc);
        c.info.put("declineType", "시장 동반 하락");
        return c;
    }

    static Map<String, Object> msi(String asOf, double score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", asOf); m.put("score", score); m.put("band", "극단적 공포"); m.put("W", 75.0); m.put("provisional", true);
        return m;
    }

    /** asOf까지의 바만 담은 컨텍스트. */
    static MarketContext ctx(DemoProvider p, String market, List<Bar> stock, List<Bar> index, LocalDate asOf, String sym) {
        MarketContext c = new MarketContext(market, asOf, p);
        Security s = new Security(market, sym, "테스트" + sym);
        s.board = "KR".equals(market) ? "KOSPI" : "NYSE";
        c.secs.put(sym, s);
        List<Bar> sb = new ArrayList<>(), ib = new ArrayList<>();
        for (Bar b : stock) if (!b.date().isAfter(asOf)) sb.add(b);
        for (Bar b : index) if (!b.date().isAfter(asOf)) ib.add(b);
        c.bars.put(sym, sb);
        c.index.put("KR".equals(market) ? "KOSPI" : "SPX", ib);
        return c;
    }

    static Db db() throws Exception {
        Path h = Files.createTempDirectory("paper-test");
        return TestDb.open(Config.forTest(h).dbPath);
    }

    static Map<String, Integer> counts(Db db) throws Exception {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String t : new String[]{"paper_signals", "paper_orders", "paper_fills", "paper_equity_daily", "paper_positions", "paper_cashflows"}) m.put(t, db.count("SELECT COUNT(*) FROM " + t));
        return m;
    }

    /** 신호(D) → 다음 거래일(D+1) 시가 체결, 비용·수량·현금·평단·관측 기록·재처리 중복 방지. */
    static void nextDayOpenFill() throws Exception {
        try (Db db = db()) {
            db.setSetting("paper_fee_buy_US", "0.001");
            db.setSetting("paper_slip_bps_US", "10");
            List<LocalDate> ds = days(30);
            List<Bar> stock = flat(ds.subList(0, 29), 100);
            LocalDate d1 = ds.get(29);
            stock.add(new Bar(d1, 105, 120, 104, 118, 2_000_000, null, true, null)); // 체결일: 시가 105, 종가 118(시가 이후 정보)
            List<Bar> idx = flat(ds, 5000);
            DemoProvider p = new DemoProvider("US");
            LocalDate d0 = ds.get(28);
            Map<String, ScoreCard> ois = Map.of("AAA", card("US", "AAA", true, true));
            new PaperTrader(db, p, ctx(p, "US", stock, idx, d0, "AAA"), 1).run(ois, msi(d0.toString(), 10));
            TestMain.eq(db.count("SELECT COUNT(*) FROM paper_orders WHERE status='PENDING' AND target_date IS NULL AND status_reason LIKE '%미확정%'"), 1, "신호 시점: 다음 거래일 미확정(미래 달력 추측 안 함)");
            TestMain.eq(db.count("SELECT COUNT(*) FROM paper_fills"), 0, "신호일에는 체결 없음");
            // 같은 신호일 재처리 → 중복 없음
            Map<String, Integer> c0 = counts(db);
            new PaperTrader(db, p, ctx(p, "US", stock, idx, d0, "AAA"), 2).run(ois, msi(d0.toString(), 10));
            TestMain.eq(counts(db), c0, "같은 신호 재처리 시 신호·주문 1회");
            // 다음 거래일 실행 → 시가 체결
            new PaperTrader(db, p, ctx(p, "US", stock, idx, d1, "AAA"), 3).run(Map.of(), msi(d1.toString(), 12));
            TestMain.eq(db.query("SELECT target_date FROM paper_orders").get(0).get("target_date"), d1.toString(), "체결 시 목표일 = 실제 다음 거래일");
            Map<String, Object> f = db.query("SELECT * FROM paper_fills").get(0);
            double plan = 100_000 * 0.10 * 0.20; // 시작금×계획비율×1차 20%
            double price = 105 * 1.001;
            double qty = Math.floor(plan / (price * 1.001));
            TestMain.eq(f.get("fill_date"), d1.toString(), "체결일=다음 거래일");
            TestMain.check(Math.abs(((Number) f.get("ref_open")).doubleValue() - 105) < 1e-9, "기준 시가");
            TestMain.check(Math.abs(((Number) f.get("fill_price")).doubleValue() - price) < 1e-9, "체결가=시가×(1+슬리피지)");
            TestMain.eq(((Number) f.get("qty")).doubleValue(), qty, "수량");
            TestMain.check(Math.abs(((Number) f.get("prev_close")).doubleValue() - 100) < 1e-9, "전일 종가");
            TestMain.check(Math.abs(((Number) f.get("gap")).doubleValue() - 0.05) < 1e-9, "시가 갭 +5%");
            TestMain.check(f.get("auction_volume") == null && f.get("bid") == null && f.get("vol_1m") == null && f.get("vol_5m") == null, "동시호가·호가·분봉 결측(0 아님)");
            TestMain.check(((Number) f.get("day_volume")).doubleValue() == 2_000_000, "체결일 전체 거래량 사후 저장");
            TestMain.check(String.valueOf(f.get("participation_window")).contains("체결일 전체 거래량"), "참여율 분모 구간 표시");
            TestMain.check(String.valueOf(f.get("liquidity_assumption")).contains("유동성 확인 없는"), "유동성 가정 표시");
            Map<String, Object> obs = Json.obj(Json.parse((String) f.get("obs_json")));
            TestMain.check(Json.obj(obs.get("ref_open")).get("availableAt") != null && Json.obj(obs.get("ref_open")).get("source") != null, "관측 기준·사용 가능 시각·출처");
            TestMain.check(String.valueOf(Json.obj(obs.get("day_volume")).get("note")).contains("사후"), "사후 분석 표시");
            double cost = price * qty * 1.001;
            double cash = ((Number) db.query("SELECT cash FROM paper_accounts").get(0).get("cash")).doubleValue();
            TestMain.check(Math.abs(cash - (100_000 - cost)) < 1e-6, "현금 = 시작금 − (체결금액+수수료)");
            double avg = ((Number) db.query("SELECT avg_cost FROM paper_positions").get(0).get("avg_cost")).doubleValue();
            TestMain.check(Math.abs(avg - cost / qty) < 1e-9, "평균단가에 수수료 포함");
            // 신호 데이터 분리: 신호 행에는 신호 시점 값(전일 종가 100, 20일 평균거래량)만
            Map<String, Object> s = db.query("SELECT * FROM paper_signals").get(0);
            TestMain.check(((Number) s.get("prev_close")).doubleValue() == 100 && ((Number) s.get("avg_vol20")).doubleValue() == 1_000_000, "신호 시점 스냅샷");
            TestMain.check(s.get("msi") != null && s.get("ois_s") != null && s.get("params_json") != null && s.get("obs_json") != null, "MSI·OIS·설정·관측 메타 저장");
            // 체결 재처리 중복 방지
            Map<String, Integer> c1 = counts(db);
            new PaperTrader(db, p, ctx(p, "US", stock, idx, d1, "AAA"), 4).run(Map.of(), msi(d1.toString(), 12));
            TestMain.eq(counts(db), c1, "체결일 재처리 시 체결 1회");
            // 일별 평가: 종가 118 평가, 항등식
            Map<String, Object> e = db.query("SELECT * FROM paper_equity_daily WHERE date=?", d1.toString()).get(0);
            TestMain.check(Math.abs(((Number) e.get("equity")).doubleValue() - (cash + qty * 118)) < 1e-6, "평가액 = 현금 + 수량×종가");
            double eq = ((Number) e.get("equity")).doubleValue(), dep = ((Number) e.get("deposits_cum")).doubleValue();
            TestMain.check(Math.abs(eq - dep - ((Number) e.get("realized_cum")).doubleValue() - ((Number) e.get("unrealized")).doubleValue()) < 1e-6, "평가 항등식");
            TestMain.check(e.get("fx_rate") != null && e.get("equity_krw") != null, "USD 계좌 환율·원화 환산 저장");
            // CSV
            String csv = PaperExport.csv(db, "paper_fills");
            TestMain.check(csv.startsWith("﻿") && csv.contains("fill_id,order_id,signal_id,run_id,mode,strategy_version"), "CSV 머리글·연결 ID");
            TestMain.check(PaperExport.csv(db, "paper_equity_combined").contains("total_krw"), "통합 평가 CSV");
        }
    }

    /** 체결일 시가 미확인 → 체결 기록 없이 사유 저장, 현금 불변. */
    static void missingOpenUnfilled() throws Exception {
        try (Db db = db()) {
            List<LocalDate> ds = days(30);
            List<Bar> stock = flat(ds.subList(0, 29), 100); // 체결일 바 없음
            List<Bar> idx = flat(ds, 5000);
            DemoProvider p = new DemoProvider("US");
            LocalDate d0 = ds.get(28), d1 = ds.get(29);
            new PaperTrader(db, p, ctx(p, "US", stock, idx, d0, "BBB"), 1).run(Map.of("BBB", card("US", "BBB", true, true)), msi(d0.toString(), 10));
            new PaperTrader(db, p, ctx(p, "US", stock, idx, d1, "BBB"), 2).run(Map.of(), msi(d1.toString(), 10));
            TestMain.eq(db.count("SELECT COUNT(*) FROM paper_fills"), 0, "체결 기록 없음");
            Map<String, Object> o = db.query("SELECT * FROM paper_orders").get(0);
            TestMain.eq(o.get("status"), "UNFILLED", "미체결");
            TestMain.check(String.valueOf(o.get("status_reason")).contains("시가 미확인"), "사유 저장: " + o.get("status_reason"));
            TestMain.check(((Number) db.query("SELECT cash FROM paper_accounts").get(0).get("cash")).doubleValue() == 100_000, "현금 불변");
            TestMain.eq(db.count("SELECT COUNT(*) FROM paper_equity_daily WHERE n_unfilled=1"), 1, "일별 평가에 미체결 집계");
        }
    }

    /** 필수 데이터 부족 → 주문 없이 보류 사유 저장. 단계 조건이 미충족으로 확정되면 보류도 남기지 않음. */
    static void deferOnMissingData() throws Exception {
        try (Db db = db()) {
            List<LocalDate> ds = days(30);
            List<Bar> stock = flat(ds, 100), idx = flat(ds, 5000);
            DemoProvider p = new DemoProvider("US");
            LocalDate d = ds.get(29);
            new PaperTrader(db, p, ctx(p, "US", stock, idx, d, "CCC"), 1).run(Map.of("CCC", card("US", "CCC", false, true)), msi(d.toString(), 10));
            TestMain.eq(db.count("SELECT COUNT(*) FROM paper_orders"), 0, "주문 없음");
            Map<String, Object> s = db.query("SELECT * FROM paper_signals").get(0);
            TestMain.eq(s.get("decision"), "DEFER", "보류");
            TestMain.check(String.valueOf(s.get("reason")).contains("필수 재무·공시 검증 미완료"), "보류 사유");
            // MSI 미산출 + 조건 판단 불가
            new PaperTrader(db, p, ctx(p, "US", stock, idx, d, "DDD"), 2).run(Map.of("DDD", card("US", "DDD", true, null)), Map.of("reason", "테스트: MSI 미산출"));
            TestMain.check(db.count("SELECT COUNT(*) FROM paper_signals WHERE symbol='DDD' AND decision='DEFER' AND reason LIKE '%조건 판단 불가%'") == 1, "단계 조건 판단 불가 보류");
            new PaperTrader(db, p, ctx(p, "US", stock, idx, d, "EEE"), 3).run(Map.of("EEE", card("US", "EEE", false, false)), msi(d.toString(), 50));
            TestMain.eq(db.count("SELECT COUNT(*) FROM paper_signals WHERE symbol='EEE'"), 0, "조건 미충족 확정이면 신호 없음");
        }
    }

    /** 매수 후 +10% 도달 → 1/3 청산, 실현손익 = 순매도대금 − 평단×수량. */
    static void pnl() throws Exception {
        try (Db db = db()) {
            db.setSetting("paper_fee_sell_US", "0.002");
            db.setSetting("paper_slip_bps_US", "0");
            List<LocalDate> ds = days(34);
            List<Bar> stock = flat(ds.subList(0, 29), 100);
            stock.add(new Bar(ds.get(29), 100, 101, 99, 100, 1_000_000, null, true, null)); // 매수 체결일
            stock.add(new Bar(ds.get(30), 111, 112, 110, 111, 1_000_000, null, true, null)); // +11% 종가 → 청산 신호
            stock.add(new Bar(ds.get(31), 112, 113, 111, 112, 1_000_000, null, true, null)); // 매도 체결(시가 112)
            List<Bar> idx = flat(ds, 5000);
            DemoProvider p = new DemoProvider("US");
            new PaperTrader(db, p, ctx(p, "US", stock, idx, ds.get(28), "FFF"), 1).run(Map.of("FFF", card("US", "FFF", true, true)), msi(ds.get(28).toString(), 10));
            new PaperTrader(db, p, ctx(p, "US", stock, idx, ds.get(29), "FFF"), 2).run(Map.of(), msi(ds.get(29).toString(), 20));
            double q0 = ((Number) db.query("SELECT qty FROM paper_positions").get(0).get("qty")).doubleValue();
            double avg = ((Number) db.query("SELECT avg_cost FROM paper_positions").get(0).get("avg_cost")).doubleValue();
            new PaperTrader(db, p, ctx(p, "US", stock, idx, ds.get(30), "FFF"), 3).run(Map.of(), msi(ds.get(30).toString(), 40));
            TestMain.eq(db.count("SELECT COUNT(*) FROM paper_orders WHERE action='EXIT_TP1' AND status='PENDING'"), 1, "+10% 1차 차익실현 주문");
            new PaperTrader(db, p, ctx(p, "US", stock, idx, ds.get(31), "FFF"), 4).run(Map.of(), msi(ds.get(31).toString(), 40));
            Map<String, Object> f = db.query("SELECT * FROM paper_fills WHERE side='SELL'").get(0);
            double sq = Math.floor(q0 / 3);
            TestMain.eq(((Number) f.get("qty")).doubleValue(), sq, "1/3 매도");
            double net = 112 * sq * (1 - 0.002);
            TestMain.check(Math.abs(((Number) f.get("realized_pnl")).doubleValue() - (net - avg * sq)) < 1e-6, "실현손익");
            Map<String, Object> pos = db.query("SELECT * FROM paper_positions").get(0);
            TestMain.eq(((Number) pos.get("qty")).doubleValue(), q0 - sq, "잔여 수량");
            TestMain.check(String.valueOf(pos.get("exits")).contains("EXIT_TP1"), "청산 단계 기록");
            Map<String, Object> e = db.query("SELECT * FROM paper_equity_daily ORDER BY date DESC LIMIT 1").get(0);
            double eq = ((Number) e.get("equity")).doubleValue();
            TestMain.check(Math.abs(eq - ((Number) e.get("deposits_cum")).doubleValue() - ((Number) e.get("realized_cum")).doubleValue() - ((Number) e.get("unrealized")).doubleValue()) < 1e-6, "평가 항등식");
            TestMain.eq(db.count("SELECT COUNT(*) FROM paper_equity_daily"), 4, "거래 없는 날 포함 일별 평가(4거래일)");
        }
    }

    /** 한국: KRX 원자료 시가 0(거래정지·무거래) → 미체결. 체결일 거래량 등 시가 이후 정보는 판정에 쓰지 않음. */
    static void krSuspendedOpen() throws Exception {
        Path h = Files.createTempDirectory("paper-kr");
        Config cfg = Config.forTest(h);
        try (Db db = TestDb.open(cfg.dbPath)) {
            List<LocalDate> ds = days(30);
            LocalDate d0 = ds.get(28), d1 = ds.get(29);
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{d1.toString(), "000660", "KOSPI", "테스트", "", 0.0, 0.0, 0.0, 50000.0, 0.0, 0.0, 1e13, 2e8});
            db.insertKrxRows(rows);
            KrLiveProvider kp = new KrLiveProvider(cfg, new Http(cfg.cacheDir, "test"), db);
            Security s = new Security("KR", "000660", "테스트");
            var o = kp.openOn(s, d1, List.of());
            TestMain.check(o.open() == null && o.note().contains("거래정지"), "시가 0 → 미확인: " + o.note());
            var o2 = kp.openOn(s, d0, List.of());
            TestMain.check(o2.open() == null && o2.note().contains("종목 없음"), "자료 없음");
            rows.clear();
            rows.add(new Object[]{d0.toString(), "000660", "KOSPI", "테스트", "", 49000.0, 51000.0, 48000.0, 50000.0, 0.0, 0.0, 1e13, 2e8});
            db.insertKrxRows(rows);
            TestMain.check(kp.openOn(s, d0, List.of()).open() == 49000.0, "시가 존재(거래량 0이어도 시가로만 판정)");
        }
    }
}
