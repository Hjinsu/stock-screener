package screener;

import screener.engine.Classifier;
import screener.engine.Indicators;
import screener.engine.MarketContext;
import screener.engine.MasScorer;
import screener.engine.MsiCalculator;
import screener.engine.OisScorer;
import screener.engine.Pipeline;
import screener.engine.RiskRules;
import screener.engine.ScoreCard;
import screener.engine.ScoreItem;
import screener.model.Bar;
import screener.model.DisclosureCheck;
import screener.model.FlowDay;
import screener.model.Fundamentals;
import screener.model.Security;
import screener.provider.DemoProvider;
import screener.store.Db;
import screener.util.Json;
import screener.util.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 의존성 없는 오프라인 테스트 러너. 네트워크를 사용하지 않는다. */
public final class TestMain {
    private static int pass, fail;
    private static final List<String> failures = new ArrayList<>();

    interface T { void run() throws Exception; }

    public static void main(String[] a) throws Exception {
        Log.setQuiet(true);
        Map<String, T> tests = new LinkedHashMap<>();
        tests.put("JSON 파싱/직렬화 왕복", TestMain::json);
        tests.put("로그 비밀값 마스킹", TestMain::redaction);
        tests.put("백분위 산식(P=100×(작은 값+0.5×동일)/n)", TestMain::percentile);
        tests.put("MAS 필터 경계값(시총·거래대금·20/60일 수익률 포함 경계)", TestMain::boundaries);
        tests.put("미국 근사값 ±10% 경계는 '미확정'", TestMain::approxBoundary);
        tests.put("결측 항목은 0점이 아닌 미평가(W·범위 반영)", TestMain::missingNotZero);
        tests.put("필수 재무 미확인 → 확인 대기", TestMain::pendingWhenMandatoryMissing);
        tests.put("공시 위험 → 차단 / 추적 중이면 무효화", TestMain::blockAndInvalidate);
        tests.put("히스테리시스(강화 ≤67 약화, 고득점 ≤77 하향, 상향 +2)", TestMain::hysteresis);
        tests.put("공시 키워드 분류", TestMain::riskKeywords);
        tests.put("MSI: W<75 미산출, 가격·확산도 필요, 잠정 표시", TestMain::msiRules);
        tests.put("후보 자동 선별(입력 티커 없음) — 데모 공급자 E2E", TestMain::autoSelection);
        tests.put("재실행 시 중복 저장 방지", TestMain::idempotentRerun);
        tests.put("MAS·OIS·MSI 분리(합산 없음)", TestMain::separation);
        tests.put("5·10·20·40·60거래일 추적·종료·MFE/MAE 재계산 일치", TestMain::tracking);
        tests.put("데모 DB 분리", TestMain::demoDbSeparate);
        tests.put("완료일 2차 확인: 확정 봉 80% 미만이면 직전 거래일로 조정", TestMain::effectiveDay);
        tests.put("모의: 다음 거래일 시가 체결·비용·관측 기록·중복 방지·CSV", PaperTests::nextDayOpenFill);
        tests.put("모의: 시가 미확인 → 체결 없이 사유 기록", PaperTests::missingOpenUnfilled);
        tests.put("모의: 필수 데이터 부족 → 보류 사유 저장", PaperTests::deferOnMissingData);
        tests.put("모의: 손익 계산(+10% 1/3 청산, 실현손익, 평가 항등식)", PaperTests::pnl);
        tests.put("모의: KRX 시가 0(거래정지) → 미확인", PaperTests::krSuspendedOpen);
        tests.put("뉴스: 7단계 분류·광고·복제 판정", NewsTests::classify);
        tests.put("뉴스: 창 밖·중복·광고 제거, 언론사 평균, 출처 20 미만 미평가", NewsTests::aggregate);
        tests.put("뉴스: MSI 직접 점수·기준일 점수 없으면 미산출", NewsTests::msiDirect);
        tests.put("카카오: 토큰·보고 내용·중복 방지·불명확 미재발송·실패 재시도·미확정·토큰 갱신(모의 응답)", KakaoTests::all);
        tests.put("보고: 일일 요약·지표 누락·거래 0건·상태 변화 알림·재실행 중복 방지·주간(데모+모의 발송)", ReportTests::dailyAndEvents);
        tests.put("보고: 보유 종목 중대 위험은 상태가 바뀔 때만 알림", ReportTests::holdingRiskTransition);
        tests.put("이관: 체크섬 정밀도·방언 변환", MigrationTests::canonAndDialect);
        tests.put("이관: 구버전 SQLite 자동 업그레이드(스키마 v" + screener.store.Schema.latestVersion() + ")", MigrationTests::legacySqliteUpgrade);
        tests.put("이관: SQLite→MariaDB PASS·재이관 거부·원본 퇴역(MariaDB 지정 시)", MigrationTests::sqliteToMariaDb);
        for (Map.Entry<String, T> e : tests.entrySet()) {
            try {
                e.getValue().run();
                pass++;
                System.out.println("PASS  " + e.getKey());
            } catch (Throwable t) {
                fail++;
                failures.add(e.getKey() + " — " + t);
                System.out.println("FAIL  " + e.getKey() + " — " + t);
            }
        }
        TestDb.cleanup();
        System.out.println("\n결과(" + (TestDb.mariadb() ? "MariaDB" : "SQLite") + "): " + pass + " 통과, " + fail + " 실패");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(boolean c, String msg) { if (!c) throw new AssertionError(msg); }

    static void eq(Object a, Object b, String msg) { if (a == null ? b != null : !a.equals(b)) throw new AssertionError(msg + " (기대 " + b + ", 실제 " + a + ")"); }

    // ---------- 단위 ----------
    static void json() {
        Object o = Json.parse("{\"a\":[1,2.5,\"한글\\n\",null,true],\"b\":{\"c\":-3e2}}");
        String w = Json.write(o);
        eq(Json.write(Json.parse(w)), w, "왕복");
        eq(((Number) Json.obj(Json.obj(o).get("b")).get("c")).doubleValue(), -300.0, "지수 표기");
    }

    static void redaction() {
        Log.registerSecret("SECRETKEY123456");
        String r = Log.redact("GET https://x/api?crtfc_key=abc&x=1 token=zzz header SECRETKEY123456");
        check(!r.contains("abc") && !r.contains("zzz") && !r.contains("SECRETKEY123456"), "마스킹 실패: " + r);
    }

    static void percentile() {
        eq(Indicators.percentile(List.of(1.0, 2.0, 3.0, 4.0), 3.0), 62.5, "백분위");
        eq(Indicators.percentile(List.of(5.0, 5.0), 5.0), 50.0, "동일값");
    }

    /** 61세션 이상 일봉: c[t-60]=c60, c[t-20]=c20, c[t]=c0, 거래대금 고정. */
    static List<Bar> bars(LocalDate end, int n, double c60, double c20, double c0, double value, boolean approx) {
        List<Bar> b = new ArrayList<>();
        LocalDate d = end.minusDays(n * 2L);
        List<LocalDate> ds = new ArrayList<>();
        while (ds.size() < n) { if (d.getDayOfWeek().getValue() <= 5) ds.add(d); d = d.plusDays(1); }
        // 마지막 날짜를 end로 맞춤
        ds.set(n - 1, end);
        for (int i = 0; i < n; i++) {
            int fromEnd = n - 1 - i;
            double c = fromEnd >= 60 ? c60 : fromEnd > 20 ? c60 + (c20 - c60) * (60 - fromEnd) / 40.0 : fromEnd == 20 ? c20 : c20 + (c0 - c20) * (20 - fromEnd) / 20.0;
            if (fromEnd == 60) c = c60;
            if (fromEnd == 0) c = c0;
            double vol = value / c;
            b.add(new Bar(ds.get(i), c, c * 1.01, c * 0.99, c, vol, approx ? null : value, approx, null));
        }
        return b;
    }

    static MarketContext ctx(String market, LocalDate asOf, DemoProvider p) {
        return new MarketContext(market, asOf, p);
    }

    static String filterResult(ScoreCard c, String name) {
        for (Map<String, Object> f : c.filters) if (f.get("name").equals(name)) return (String) f.get("result");
        return null;
    }

    static void boundaries() {
        LocalDate asOf = LocalDate.of(2026, 11, 12);
        DemoProvider p = new DemoProvider("KR");
        // 정확히 경계: 시총 5조, 거래대금 100억, 20일 +15%, 60일 +30% → 통과
        MarketContext c = ctx("KR", asOf, p);
        Security s = new Security("KR", "000010", "경계");
        s.board = "KOSPI";
        s.marketCap = 5e12;
        c.bars.put(s.symbol, bars(asOf, 80, 10000, 13000.0 / 1.15, 13000, 1e10, false));
        ScoreCard pf = MasScorer.prefilter(s, c);
        eq(filterResult(pf, "시가총액"), "PASS", "시총 5조 정확히");
        eq(filterResult(pf, "최근 20거래일 평균 거래대금"), "PASS", "거래대금 100억 정확히");
        eq(filterResult(pf, "최근 20거래일 수익률"), "PASS", "20일 +15% 정확히");
        eq(filterResult(pf, "최근 60거래일 수익률"), "PASS", "60일 +30% 정확히");
        check(pf.filterPassed, "전체 통과");
        // 경계 바로 바깥
        s.marketCap = 5e12 - 1;
        c.bars.put(s.symbol, bars(asOf, 80, 10000, 13010.0 / 1.1501, 13010, 1e10 - 1, false));
        pf = MasScorer.prefilter(s, c);
        eq(filterResult(pf, "시가총액"), "FAIL", "시총 5조-1");
        eq(filterResult(pf, "최근 20거래일 평균 거래대금"), "FAIL", "거래대금 100억-1");
        eq(filterResult(pf, "최근 20거래일 수익률"), "FAIL", "20일 +15.01%");
        eq(filterResult(pf, "최근 60거래일 수익률"), "FAIL", "60일 +30.1%");
        check(!pf.filterPassed && !pf.filterUnconfirmed, "탈락");
        // 음수 수익률은 자동 제외하지 않음
        s.marketCap = 6e12;
        c.bars.put(s.symbol, bars(asOf, 80, 10000, 9000, 8000, 2e10, false));
        pf = MasScorer.prefilter(s, c);
        check(pf.filterPassed, "음수 수익률 허용");
        // 자료 부족(61개 종가 미만) → 미확정
        c.bars.put(s.symbol, bars(asOf, 40, 10000, 10000, 10000, 2e10, false));
        pf = MasScorer.prefilter(s, c);
        eq(filterResult(pf, "최근 60거래일 수익률"), "UNCONFIRMED", "60일 자료 부족");
        // 유형 제외
        s.etf = true;
        c.bars.put(s.symbol, bars(asOf, 80, 10000, 10000, 10000, 2e10, false));
        pf = MasScorer.prefilter(s, c);
        eq(filterResult(pf, "유형 제외(ETF·우선주·SPAC·관리/정지 등)"), "FAIL", "ETF 제외");
    }

    static void approxBoundary() {
        LocalDate asOf = LocalDate.of(2026, 11, 12);
        DemoProvider p = new DemoProvider("US");
        MarketContext c = ctx("US", asOf, p);
        Security s = new Security("US", "TST", "Test");
        s.board = "NYSE";
        s.marketCap = 1.05e10;
        s.mcapApprox = true;
        c.bars.put(s.symbol, bars(asOf, 80, 100, 100, 100, 5e7, true));
        eq(filterResult(MasScorer.prefilter(s, c), "시가총액"), "UNCONFIRMED", "근사 시총 경계 +5%");
        s.marketCap = 1.2e10;
        eq(filterResult(MasScorer.prefilter(s, c), "시가총액"), "PASS", "근사 시총 +20%");
        s.marketCap = 0.8e10;
        eq(filterResult(MasScorer.prefilter(s, c), "시가총액"), "FAIL", "근사 시총 -20%");
        c.bars.put(s.symbol, bars(asOf, 80, 100, 100, 100, 2.1e7, true));
        eq(filterResult(MasScorer.prefilter(s, c), "최근 20거래일 평균 거래대금"), "UNCONFIRMED", "근사 거래대금 경계");
    }

    static void missingNotZero() {
        LocalDate asOf = LocalDate.of(2026, 11, 12);
        DemoProvider p = new DemoProvider("KR");
        MarketContext c = ctx("KR", asOf, p);
        Security s = new Security("KR", "000020", "결측");
        s.board = "KOSPI";
        s.marketCap = 8e12;
        c.bars.put(s.symbol, bars(asOf, 80, 100, 100, 101, 3e10, false));
        // 수급·재무·지수 모두 없음
        ScoreCard card = MasScorer.score(s, c);
        for (ScoreItem it : card.items) {
            if (it.area.equals(MasScorer.A_FLOW) || it.area.equals(MasScorer.A_FIN)) check(it.grade == null, it.name + " 은 미평가여야 함(0점 금지)");
        }
        check(card.W() < 100, "W<100");
        eq(card.rangeHigh(), ScoreCard.round1(card.S() + 100 - card.W()), "가능 범위 상단 = S+100-W");
        check(!card.missing.isEmpty(), "누락 목록 기록");
        // 연기금은 수급이 있어도 미평가
        List<FlowDay> fl = new ArrayList<>();
        for (Bar b : c.bars.get(s.symbol)) fl.add(new FlowDay(b.date(), 1000.0, 1000.0, null, 30.0 + fl.size() * 0.01, b.close()));
        c.flows.put(s.symbol, fl);
        card = MasScorer.score(s, c);
        ScoreItem pension = card.items.stream().filter(i -> i.key.equals("pension")).findFirst().get();
        check(pension.grade == null, "연기금 미평가");
        ScoreItem fr = card.items.stream().filter(i -> i.key.equals("foreign")).findFirst().get();
        eq(fr.grade, 1.0, "외국인 20일·5일 양수 → 충족");
        // 미국: 수급 25점 전부 미평가
        DemoProvider pu = new DemoProvider("US");
        MarketContext cu = ctx("US", asOf, pu);
        Security su = new Security("US", "TST", "t");
        su.marketCap = 2e10;
        cu.bars.put("TST", bars(asOf, 80, 100, 100, 101, 5e7, true));
        ScoreCard cardU = MasScorer.score(su, cu);
        double flowEval = cardU.items.stream().filter(i -> i.area.equals(MasScorer.A_FLOW) && i.evaluated()).count();
        eq(flowEval, 0.0, "미국 수급 미평가");
    }

    static void pendingWhenMandatoryMissing() {
        ScoreCard c = fakeCard("MAS", 72);
        Classifier.finalizeMas(c, RiskRules.financial(null, LocalDate.of(2026, 11, 12)), okDisclosure(), null, false, "t");
        eq(c.status, "PENDING", "재무 미확보 → 확인 대기");
        ScoreCard c2 = fakeCard("MAS", 72);
        Classifier.finalizeMas(c2, RiskRules.financial(goodFund(), LocalDate.of(2026, 11, 12)), null, null, false, "t");
        eq(c2.status, "PENDING", "공시 미스캔 → 확인 대기");
        ScoreCard c3 = fakeCard("MAS", 72);
        Classifier.finalizeMas(c3, RiskRules.financial(goodFund(), LocalDate.of(2026, 11, 12)), okDisclosure(), null, false, "t");
        eq(c3.status, "STRONG", "모두 확인 → 강화");
    }

    static void blockAndInvalidate() {
        DisclosureCheck d = okDisclosure();
        d.events.add(new DisclosureCheck.Event("2026-11-01", "횡령ㆍ배임혐의발생", DisclosureCheck.Level.BLOCK, null, null));
        ScoreCard c = fakeCard("MAS", 85);
        Classifier.finalizeMas(c, RiskRules.financial(goodFund(), LocalDate.of(2026, 11, 12)), d, null, false, "t");
        eq(c.status, "BLOCKED", "신규: 점수와 무관하게 차단");
        ScoreCard c2 = fakeCard("MAS", 85);
        Classifier.finalizeMas(c2, RiskRules.financial(goodFund(), LocalDate.of(2026, 11, 12)), d, "STRONG", true, "t");
        eq(c2.status, "INVALIDATED", "추적 중: 무효화");
        DisclosureCheck r = okDisclosure();
        r.events.add(new DisclosureCheck.Event("2026-11-01", "주요사항보고서(유상증자결정)", DisclosureCheck.Level.REVIEW, null, null));
        ScoreCard c3 = fakeCard("MAS", 85);
        Classifier.finalizeMas(c3, RiskRules.financial(goodFund(), LocalDate.of(2026, 11, 12)), r, null, false, "t");
        eq(c3.status, "PENDING", "규모 미확인 희석 공시 → 확인 대기");
        Fundamentals bad = goodFund();
        bad.totalEquity = 1.0;
        bad.capitalStock = 10.0;
        ScoreCard c4 = fakeCard("MAS", 85);
        Classifier.finalizeMas(c4, RiskRules.financial(bad, LocalDate.of(2026, 11, 12)), okDisclosure(), null, false, "t");
        eq(c4.status, "BLOCKED", "자본잠식 차단");
        Fundamentals buyback = goodFund();
        buyback.totalEquity = -5.0; buyback.capitalStock = null; buyback.ocf = 30.0;
        eq(RiskRules.financial(buyback, LocalDate.of(2026, 11, 12)).status(), "CONFIRMED", "자본 음수라도 영업이익·현금흐름 양호하면 차단 안 함");
        buyback.opIncome = -1.0;
        eq(RiskRules.financial(buyback, LocalDate.of(2026, 11, 12)).status(), "BLOCK", "자본 음수 + 영업적자 → 차단");
    }

    static void hysteresis() {
        eq(Classifier.hysteresis("STRONG", "EARLY", 68), "STRONG", "강화 68 유지");
        eq(Classifier.hysteresis("STRONG", "EARLY", 67), "WEAKENED", "강화 67 약화");
        eq(Classifier.hysteresis("HIGH", "STRONG", 78), "HIGH", "고득점 78 유지");
        eq(Classifier.hysteresis("HIGH", "STRONG", 77), "STRONG", "고득점 77 강화 하향");
        eq(Classifier.hysteresis("EARLY", "STRONG", 71), "EARLY", "상향 버퍼 미달");
        eq(Classifier.hysteresis("EARLY", "STRONG", 72), "STRONG", "상향 버퍼 충족");
        eq(Classifier.hysteresis("WEAKENED", "NONE", 45), "INVALIDATED", "약화 후 50 미만 무효화");
        eq(Classifier.hysteresis("INVALIDATED", "WATCH", 58), "INVALIDATED", "무효화 후 관찰 수준 복귀 안 함");
        eq(Classifier.hysteresis("INVALIDATED", "EARLY", 63), "EARLY", "무효화 후 62+ 복귀");
    }

    static void riskKeywords() {
        eq(RiskRules.classifyKr("주요사항보고서(유상증자결정)"), DisclosureCheck.Level.REVIEW, "유상증자");
        eq(RiskRules.classifyKr("주권매매거래정지해제"), DisclosureCheck.Level.INFO, "정지 해제");
        eq(RiskRules.classifyKr("횡령ㆍ배임혐의발생"), DisclosureCheck.Level.BLOCK, "횡령");
        eq(RiskRules.classifyKr("임원ㆍ주요주주특정증권등소유상황보고서"), DisclosureCheck.Level.INFO, "참고");
        eq(RiskRules.classifyUs("8-K", "4.02,9.01"), DisclosureCheck.Level.BLOCK, "8-K 4.02");
        eq(RiskRules.classifyUs("424B2", ""), DisclosureCheck.Level.INFO, "채권 발행 설명서는 참고");
    }

    static void msiRules() {
        List<LocalDate> cal = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 1, 1);
        while (cal.size() < 300) { if (d.getDayOfWeek().getValue() <= 5) cal.add(d); d = d.plusDays(1); }
        Map<LocalDate, Double> s1 = new HashMap<>(), s2 = new HashMap<>(), s3 = new HashMap<>();
        for (int i = 0; i < cal.size(); i++) { s1.put(cal.get(i), Math.sin(i / 7.0)); s2.put(cal.get(i), Math.cos(i / 11.0)); s3.put(cal.get(i), (double) (i % 13)); }
        MsiCalculator m = new MsiCalculator("US", cal);
        m.add("가격·변동성", "g", "a", 1, false, "t", s1);
        Map<String, Object> r = m.compute(cal.get(cal.size() - 1));
        check(r.get("score") == null, "가격만(W=35) → 미산출");
        m.add("시장 확산도", "g", "b", 1, false, "t", s2);
        r = m.compute(cal.get(cal.size() - 1));
        check(r.get("score") == null, "W=60 → 미산출");
        m.add("수급·파생", "g", "c", -1, true, "t", s3);
        r = m.compute(cal.get(cal.size() - 1));
        check(r.get("score") != null, "W=75 → 산출");
        eq(r.get("provisional"), true, "뉴스 누락·대체 → 잠정");
        check(r.get("ch5") != null, "동일 구성 5일 변화");
        // 비교값 120 미만이면 지표 미산출
        MsiCalculator m2 = new MsiCalculator("KR", cal);
        m2.add("가격·변동성", "g", "a", 1, false, "t", s1);
        r = m2.compute(cal.get(100));
        check(r.get("score") == null, "비교값 부족");
    }

    // ---------- 통합(데모 공급자, 오프라인) ----------
    static Path tmp() throws Exception { return Files.createTempDirectory("screener-test"); }

    static void autoSelection() throws Exception {
        Path h = tmp();
        Config cfg = Config.forTest(h);
        try (Db db = TestDb.open(cfg.dbPath)) {
            DemoProvider p = new DemoProvider("KR");
            p.setAsOf(p.calendar().get(DemoProvider.DAYS - 70)); // 매집 구간
            Map<String, Object> sum = new Pipeline(p, db, cfg).run();
            eq(sum.get("status"), "OK", "실행 상태");
            check(((Number) sum.get("listingsTotal")).intValue() == 30, "공급자 상장 목록 전체 수집");
            @SuppressWarnings("unchecked") Map<String, Object> te = (Map<String, Object>) sum.get("typeExcluded");
            check(te.containsKey("우선주") && te.containsKey("SPAC") && te.containsKey("ETF/ETN"), "ETF·우선주·SPAC 자동 제외: " + te);
            String acc = db.query("SELECT status FROM scores WHERE model='MAS' AND symbol='900010'").get(0).get("status").toString();
            check(List.of("EARLY", "STRONG", "HIGH", "WATCH").contains(acc), "매집 패턴 종목 자동 선별: " + acc);
            eq(db.count("SELECT COUNT(*) FROM scores WHERE model='MAS' AND symbol='900050'"), 0, "소형주(시총 미달) 미평가");
            eq(db.count("SELECT COUNT(*) FROM scores WHERE model='MAS' AND symbol='900060'"), 0, "비유동 종목(거래대금 미달) 제외");
            String nof = db.query("SELECT status FROM scores WHERE model='MAS' AND symbol='900070'").get(0).get("status").toString();
            eq(nof, "PENDING", "재무 미확보 종목 → 확인 대기");
            String risk = db.query("SELECT status FROM scores WHERE model='MAS' AND symbol='900080'").get(0).get("status").toString();
            eq(risk, "PENDING", "유상증자 공시 → 확인 대기");
            check(db.count("SELECT COUNT(*) FROM msi WHERE market='KR'") == 1, "MSI 저장");
        }
    }

    static Map<String, Integer> counts(Db db) throws Exception {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String t : new String[]{"scores", "transitions", "alerts", "tracking", "checkpoints", "msi", "candidate_state"}) m.put(t, db.count("SELECT COUNT(*) FROM " + t));
        return m;
    }

    static void idempotentRerun() throws Exception {
        Path h = tmp();
        Config cfg = Config.forTest(h);
        try (Db db = TestDb.open(cfg.dbPath)) {
            DemoProvider p = new DemoProvider("KR");
            List<LocalDate> cal = p.calendar();
            for (int i = DemoProvider.DAYS - 70; i < DemoProvider.DAYS - 55; i++) { p.setAsOf(cal.get(i)); new Pipeline(p, db, cfg).run(); }
            Map<String, Integer> before = counts(db);
            List<Map<String, Object>> st1 = db.query("SELECT model, symbol, state, since FROM candidate_state ORDER BY model, symbol");
            new Pipeline(p, db, cfg).run(); // 같은 기준일 재실행
            new Pipeline(p, db, cfg).run();
            Map<String, Integer> after = counts(db);
            eq(after, before, "재실행 후 행 수 동일");
            eq(db.query("SELECT model, symbol, state, since FROM candidate_state ORDER BY model, symbol"), st1, "상태 동일");
            eq(db.count("SELECT COUNT(*) FROM runs"), 17, "실행 내역은 매번 기록");
        }
    }

    static void separation() throws Exception {
        Path h = tmp();
        Config cfg = Config.forTest(h);
        try (Db db = TestDb.open(cfg.dbPath)) {
            DemoProvider p = new DemoProvider("KR");
            p.setAsOf(p.calendar().get(DemoProvider.DAYS - 70)); // 매집 구간(MAS 필터 통과 시점)
            new Pipeline(p, db, cfg).run();
            check(db.count("SELECT COUNT(*) FROM scores WHERE model NOT IN ('MAS','OIS')") == 0, "모델 구분");
            // 같은 종목의 MAS와 OIS는 서로 다른 행이며 각 S는 자기 항목 합과 같다(다른 점수·MSI 미포함)
            for (Map<String, Object> r : db.query("SELECT model, s, card_json FROM scores WHERE symbol='900010'")) {
                Map<String, Object> card = Json.obj(Json.parse((String) r.get("card_json")));
                double sum = 0;
                for (Object it : Json.arr(card.get("items"))) { Object pts = Json.obj(it).get("points"); if (pts != null) sum += ((Number) pts).doubleValue(); }
                eq(ScoreCard.round1(sum), ((Number) r.get("s")).doubleValue(), r.get("model") + " S = 자기 항목 합");
                for (Object it : Json.arr(card.get("items"))) {
                    String area = (String) Json.obj(it).get("area");
                    if ("MAS".equals(r.get("model"))) check(!area.equals(OisScorer.A_REV) && !area.equals(OisScorer.A_DD), "MAS에 OIS 영역 없음");
                    else check(!area.equals(MasScorer.A_VOLA) && !area.equals(MasScorer.A_PRICE), "OIS에 MAS 영역 없음");
                }
            }
            eq(db.count("SELECT COUNT(*) FROM scores WHERE symbol='900010'"), 2, "MAS·OIS 각 1행");
            // MSI 입력이 바뀌어도 MAS 점수는 불변
            MarketContext c = new MarketContext("KR", p.calendar().get(DemoProvider.DAYS - 1), p);
            List<Security> u = p.listUniverse(c.asOf);
            Security s = u.stream().filter(x -> x.symbol.equals("900010")).findFirst().get();
            c.secs.put(s.symbol, s);
            c.bars.putAll(p.bars(List.of(s), c.asOf));
            c.index.putAll(p.indexBars(c.asOf));
            c.flows.putAll(p.flows(List.of(s), c.asOf, 70));
            c.fundamentals.putAll(p.fundamentals(List.of(s), c.asOf));
            double a = MasScorer.score(s, c).S();
            c.msiScore = 5.0; c.msiCh5 = 20.0;
            double b = MasScorer.score(s, c).S();
            double o1 = OisScorer.score(s, c).S();
            c.msiScore = 95.0;
            double o2 = OisScorer.score(s, c).S();
            eq(a, b, "MSI 변화가 MAS에 영향 없음");
            eq(o1, o2, "MSI 변화가 OIS 점수에 영향 없음(시나리오에만 사용)");
        }
    }

    static void tracking() throws Exception {
        Path h = tmp();
        Config cfg = Config.forTest(h);
        try (Db db = TestDb.open(cfg.dbPath)) {
            DemoProvider p = new DemoProvider("KR");
            List<LocalDate> cal = p.calendar();
            for (int i = DemoProvider.DAYS - 70; i < DemoProvider.DAYS; i++) { p.setAsOf(cal.get(i)); new Pipeline(p, db, cfg).run(); }
            List<Map<String, Object>> ended = db.query("SELECT * FROM tracking WHERE status='ENDED' AND end_reason LIKE '60거래일%'");
            check(!ended.isEmpty(), "60거래일 종료된 추적 존재");
            Map<String, List<Bar>> all = p.bars(new ArrayList<>(p.listUniverse(cal.get(DemoProvider.DAYS - 1))), cal.get(DemoProvider.DAYS - 1));
            for (Map<String, Object> t : ended) {
                long id = ((Number) t.get("id")).longValue();
                List<Map<String, Object>> cps = db.query("SELECT * FROM checkpoints WHERE tracking_id=? ORDER BY horizon", id);
                eq(cps.size(), 5, "체크포인트 5개");
                List<Integer> hs = new ArrayList<>();
                for (Map<String, Object> cp : cps) hs.add(((Number) cp.get("horizon")).intValue());
                eq(hs, List.of(5, 10, 20, 40, 60), "기간");
                // 재계산 일치
                List<Bar> b = all.get((String) t.get("symbol"));
                int i0 = -1;
                for (int k = 0; k < b.size(); k++) if (b.get(k).date().toString().equals(t.get("detect_date"))) i0 = k;
                double c0 = ((Number) t.get("detect_close")).doubleValue();
                for (Map<String, Object> cp : cps) {
                    int hh = ((Number) cp.get("horizon")).intValue();
                    double mx = Double.NEGATIVE_INFINITY, mn = Double.POSITIVE_INFINITY;
                    for (int k = i0 + 1; k <= i0 + hh; k++) { mx = Math.max(mx, b.get(k).high()); mn = Math.min(mn, b.get(k).low()); }
                    check(Math.abs(((Number) cp.get("ret")).doubleValue() - (b.get(i0 + hh).close() / c0 - 1)) < 1e-9, "수익률 일치");
                    check(Math.abs(((Number) cp.get("mfe")).doubleValue() - (mx / c0 - 1)) < 1e-9, "MFE 일치");
                    check(Math.abs(((Number) cp.get("mae")).doubleValue() - (mn / c0 - 1)) < 1e-9, "MAE 일치");
                    eq(cp.get("date"), b.get(i0 + hh).date().toString(), "체크포인트 날짜");
                }
            }
            check(db.count("SELECT COUNT(*) FROM (SELECT tracking_id, horizon, COUNT(*) n FROM checkpoints GROUP BY 1,2 HAVING n>1) t") == 0, "체크포인트 중복 없음");
            check(db.count("SELECT COUNT(*) FROM (SELECT model, symbol, COUNT(*) n FROM tracking WHERE status='ACTIVE' GROUP BY 1,2 HAVING n>1) t") == 0, "종목당 활성 추적 1개");
            check(db.count("SELECT COUNT(*) FROM tracking WHERE model='OIS'") > 0, "OIS도 별도 추적");
            // 추적 종료가 발생한 기준일들을 다시 실행해도 행 수·상태 불변
            List<Map<String, Object>> endDays = db.query("SELECT DISTINCT end_as_of FROM tracking WHERE end_as_of IS NOT NULL ORDER BY end_as_of DESC LIMIT 1");
            check(!endDays.isEmpty(), "종료 기준일 기록");
            Map<String, Integer> before = counts(db);
            List<Map<String, Object>> st1 = db.query("SELECT model, symbol, state FROM candidate_state ORDER BY model, symbol");
            p.setAsOf(cal.get(DemoProvider.DAYS - 1));
            new Pipeline(p, db, cfg).run();
            eq(counts(db), before, "마지막 기준일 재실행 후 행 수 동일");
            eq(db.query("SELECT model, symbol, state FROM candidate_state ORDER BY model, symbol"), st1, "재실행 후 상태 동일");
        }
    }

    static void demoDbSeparate() {
        Config live = Config.load(false, 0), demo = Config.load(true, 0);
        check(live.dbPath.getFileName().toString().equals("screener.db"), "실데이터 DB 이름");
        check(demo.dbPath.getFileName().toString().equals("demo.db"), "데모 DB 이름");
    }

    static void effectiveDay() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 11, 11), d1 = LocalDate.of(2026, 11, 12);
        Map<String, List<Bar>> bars = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            List<Bar> l = new ArrayList<>();
            l.add(Bar.of(d0, 1, 1, 1, 1, 1));
            if (i == 0) l.add(Bar.of(d1, 1, 1, 1, 1, 1)); // 1/10만 당일 확정 봉
            bars.put("S" + i, l);
        }
        Map<String, List<Bar>> idx = Map.of("SPX", List.of(Bar.of(d0, 1, 1, 1, 1, 1), Bar.of(d1, 1, 1, 1, 1, 1)));
        java.lang.reflect.Method mm = Pipeline.class.getDeclaredMethod("effectiveDay", Map.class, LocalDate.class, Map.class);
        mm.setAccessible(true);
        eq(mm.invoke(null, bars, d1, idx), d0, "80% 미만 → 직전 거래일");
        for (List<Bar> l : bars.values()) if (l.size() == 1) l.add(Bar.of(d1, 1, 1, 1, 1, 1));
        eq(mm.invoke(null, bars, d1, idx), d1, "충분하면 그대로");
    }

    // ---------- 도우미 ----------
    static ScoreCard fakeCard(String model, double s) {
        ScoreCard c = new ScoreCard(model, "test", "KR", "X");
        c.name = "X";
        c.asOf = "2026-11-12";
        c.filterPassed = true;
        // 6개 영역 중 3개에 신호, 총 배점 100(전부 평가)
        double[][] spec = {{25, 0}, {20, 0}, {20, 0}, {15, 0}, {10, 0}, {10, 0}};
        String[] areas = {MasScorer.A_FLOW, MasScorer.A_VOL, MasScorer.A_PRICE, MasScorer.A_RS, MasScorer.A_VOLA, MasScorer.A_FIN};
        double remain = s;
        for (int i = 0; i < 6; i++) {
            double give = Math.min(spec[i][0], remain);
            remain -= give;
            ScoreItem it = new ScoreItem(areas[i], "k" + i, "항목" + i, spec[i][0], "test");
            it.grade(give / spec[i][0], "obs");
            c.add(it);
        }
        return c;
    }

    static DisclosureCheck okDisclosure() {
        DisclosureCheck d = new DisclosureCheck();
        d.ok = true;
        d.source = "test";
        d.window = "test";
        return d;
    }

    static Fundamentals goodFund() {
        Fundamentals f = new Fundamentals();
        f.revenue = 110.0; f.revenuePrev = 100.0; f.opIncome = 12.0; f.opIncomePrev = 10.0;
        f.totalLiab = 50.0; f.totalEquity = 100.0; f.capitalStock = 5.0; f.currentAssets = 80.0; f.currentLiab = 40.0;
        f.periodEnd = "2026-09-30";
        return f;
    }
}
