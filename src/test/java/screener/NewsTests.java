package screener;

import screener.engine.MsiCalculator;
import screener.engine.NewsSentiment;
import screener.source.NaverNewsSource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 뉴스 심리 오프라인 테스트(네트워크 없음). */
final class NewsTests {
    private NewsTests() { }

    static void classify() {
        TestMain.eq(NewsSentiment.classify("<b>코스피</b> 폭락…서킷브레이커 발동"), 0, "극단적 공포");
        TestMain.eq(NewsSentiment.classify("개인 투매에 반대매매 쏟아져 하락"), 1, "패닉셀");
        TestMain.eq(NewsSentiment.classify("외국인 순매도에 코스피 하락 마감"), 2, "비관");
        TestMain.eq(NewsSentiment.classify("코스피 보합권 등락"), 3, "중립");
        TestMain.eq(NewsSentiment.classify("코스피 반등, 외국인 순매수"), 4, "낙관");
        TestMain.eq(NewsSentiment.classify("코스피 사상 최고치 경신"), 5, "과도한 낙관");
        TestMain.eq(NewsSentiment.classify("불장 광풍, 사상 최고 랠리"), 6, "극단적 환희");
        TestMain.eq(NewsSentiment.classify("[마감시황] 코스피 2.6%·코스닥 5.6%↑…코스닥 연속 매수 사이드카"), 4, "매수 사이드카·↑ = 강세");
        TestMain.eq(NewsSentiment.classify("코스피 급락에 매도 사이드카 발동"), 0, "매도 사이드카 = 공포");
        TestMain.check(NewsSentiment.isAd("[광고] 급등주 무료 추천 리딩방"), "광고 판정");
        TestMain.check(!NewsSentiment.isAd("[속보] 코스피 하락"), "속보는 광고 아님");
        TestMain.eq(NewsSentiment.dupKey("[속보] 코스피, 2% 하락…외국인 매도"), NewsSentiment.dupKey("코스피 2% 하락 외국인 매도 (종합)"), "복제 기사 같은 키");
    }

    static NaverNewsSource.Item item(String title, String outlet, ZonedDateTime t) {
        return new NaverNewsSource.Item(title, "", "https://n.news.naver.com/x/" + title.hashCode(), "https://www." + outlet + "/a/" + title.hashCode(), t);
    }

    static void aggregate() {
        ZoneId z = ZoneId.of("Asia/Seoul");
        ZonedDateTime from = ZonedDateTime.of(2026, 11, 11, 15, 30, 0, 0, z), to = ZonedDateTime.of(2026, 11, 12, 15, 30, 0, 0, z);
        ZonedDateTime in = to.minusHours(2);
        List<NaverNewsSource.Item> l = new ArrayList<>();
        // 19개 언론사: 하락(비관=30)
        for (int i = 0; i < 19; i++) l.add(item("코스피 하락 마감 " + i + "번째 소식", "press" + i + ".co.kr", in));
        l.add(item("코스피 하락 마감 0번째 소식", "copy.co.kr", in));          // 제목 복제 → 중복 제거
        l.add(item("[광고] 급등주 무료 추천 리딩방 안내", "ad.co.kr", in));  // 광고 제거
        l.add(item("창 밖 기사 하락 소식입니다", "late.co.kr", to.plusMinutes(5))); // 창 이후(미래 정보) 제외
        Map<String, Object> r = NewsSentiment.aggregate(l, from, to);
        TestMain.eq(r.get("duplicates"), 1, "중복 1");
        TestMain.eq(r.get("ads"), 1, "광고 1");
        TestMain.eq(r.get("outsideWindow"), 1, "창 밖 1(기준 시각 이후 기사 미사용)");
        TestMain.eq(r.get("outlets"), 19, "독립 출처 19");
        TestMain.check(r.get("score") == null && "INSUFFICIENT".equals(r.get("status")), "출처 20 미만 → 미평가(0점 아님)");
        // 한 언론사가 기사를 대량 게시해도 언론사 평균으로 영향 제한
        for (int i = 0; i < 30; i++) l.add(item("코스피 사상 최고치 경신 축포 " + i, "spam.co.kr", in));
        l.add(item("코스피 하락 마감 마지막 소식", "press19.co.kr", in));
        r = NewsSentiment.aggregate(l, from, to);
        TestMain.eq(r.get("outlets"), 21, "독립 출처 21");
        double expect = (20 * 30.0 + 85.0) / 21; // 비관 20곳 + 과도한 낙관 1곳(30건)
        TestMain.check(Math.abs(((Number) r.get("score")).doubleValue() - expect) < 1e-9, "언론사별 평균 후 평균: " + r.get("score"));
    }

    static void msiDirect() {
        List<LocalDate> cal = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 1, 1);
        while (cal.size() < 300) { if (d.getDayOfWeek().getValue() <= 5) cal.add(d); d = d.plusDays(1); }
        Map<LocalDate, Double> s1 = new HashMap<>(), s2 = new HashMap<>(), s3 = new HashMap<>();
        for (int i = 0; i < cal.size(); i++) { s1.put(cal.get(i), Math.sin(i / 7.0)); s2.put(cal.get(i), Math.cos(i / 11.0)); s3.put(cal.get(i), (double) (i % 13)); }
        LocalDate t = cal.get(cal.size() - 1);
        MsiCalculator m = new MsiCalculator("US", cal);
        m.add("가격·변동성", "g", "a", 1, false, "t", s1);
        m.add("시장 확산도", "g", "b", 1, false, "t", s2);
        m.addDirect("뉴스·커뮤니티", "뉴스", "news", true, "t", Map.of(t, 30.0)); // 기준일 하루치만 있어도 직접 점수 사용
        Map<String, Object> r = m.compute(t);
        TestMain.check(r.get("score") != null, "가격35+확산25+뉴스25=85 → 산출");
        TestMain.eq(((Number) r.get("W")).doubleValue(), 85.0, "W=85(수급·파생 미평가)");
        TestMain.check(r.get("ch5") == null, "과거 뉴스 점수 없으면 동일 구성 변화량 미산출");
        MsiCalculator m2 = new MsiCalculator("US", cal);
        m2.add("가격·변동성", "g", "a", 1, false, "t", s1);
        m2.add("시장 확산도", "g", "b", 1, false, "t", s2);
        m2.addDirect("뉴스·커뮤니티", "뉴스", "news", true, "t", Map.of(cal.get(10), 30.0)); // 기준일 점수 없음
        TestMain.check(m2.compute(t).get("score") == null, "뉴스 기준일 점수 없으면 W=60 → 미산출(추정 금지)");
    }
}
