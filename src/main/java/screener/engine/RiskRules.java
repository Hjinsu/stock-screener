package screener.engine;

import screener.model.DisclosureCheck;
import screener.model.Fundamentals;
import screener.util.Num;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 재무·공시 필수 검증과 중대 위험 분류(app-7, app-8).
 * BLOCK=신규 등록 차단, REVIEW=확인 대기(규모·방향 미확인), INFO=참고.
 */
public final class RiskRules {
    private RiskRules() { }

    /** 한국 공시 제목 키워드. */
    public static DisclosureCheck.Level classifyKr(String title) {
        String t = title.replace(" ", "");
        if (t.contains("해제") || t.contains("철회") || t.contains("해소")) return DisclosureCheck.Level.INFO;
        String[] block = {"상장폐지", "관리종목지정", "매매거래정지", "거래정지", "감사의견거절", "의견거절", "부적정",
                "회생절차", "파산", "부도", "횡령", "배임", "불성실공시법인지정", "자본잠식", "해산사유"};
        String[] review = {"유상증자결정", "전환사채권발행결정", "신주인수권부사채권발행결정", "교환사채권발행결정",
                "감자결정", "최대주주변경", "투자주의환기종목", "소송등의제기", "한정의견", "영업정지", "공급계약해지"};
        for (String k : block) if (t.contains(k)) return DisclosureCheck.Level.BLOCK;
        for (String k : review) if (t.contains(k)) return DisclosureCheck.Level.REVIEW;
        return DisclosureCheck.Level.INFO;
    }

    public static String krRuleOf(String title) {
        String t = title.replace(" ", "");
        for (String k : new String[]{"상장폐지", "관리종목지정", "매매거래정지", "거래정지", "감사의견거절", "의견거절", "부적정",
                "회생절차", "파산", "부도", "횡령", "배임", "불성실공시법인지정", "자본잠식", "해산사유",
                "유상증자결정", "전환사채권발행결정", "신주인수권부사채권발행결정", "교환사채권발행결정", "감자결정",
                "최대주주변경", "투자주의환기종목", "소송등의제기", "한정의견", "영업정지", "공급계약해지"})
            if (t.contains(k)) return k;
        return null;
    }

    /** 미국 SEC 공시: 양식·8-K 항목. */
    public static DisclosureCheck.Level classifyUs(String form, String items) {
        String f = form == null ? "" : form.trim();
        String it = items == null ? "" : items;
        if (it.contains("4.02") || it.contains("1.03") || it.contains("3.01")) return DisclosureCheck.Level.BLOCK;
        if (f.equals("NT 10-K") || f.equals("NT 10-Q") || f.equals("NT 20-F")) return DisclosureCheck.Level.REVIEW;
        if (it.contains("4.01")) return DisclosureCheck.Level.REVIEW;
        if (f.equals("S-1") || f.equals("F-1") || f.equals("424B4") || f.equals("S-1/A")) return DisclosureCheck.Level.REVIEW;
        return DisclosureCheck.Level.INFO;
    }

    public static String usRuleOf(String form, String items) {
        String it = items == null ? "" : items;
        if (it.contains("4.02")) return "8-K 4.02 재무제표 신뢰 불가";
        if (it.contains("1.03")) return "8-K 1.03 파산·법정관리";
        if (it.contains("3.01")) return "8-K 3.01 상장유지 요건 미달 통지";
        if (it.contains("4.01")) return "8-K 4.01 감사인 변경";
        if (form != null && form.startsWith("NT ")) return form + " 정기보고서 지연 제출";
        if (form != null && (form.startsWith("S-1") || form.equals("F-1") || form.equals("424B4"))) return form + " 증권 발행(희석 가능, 규모 미확인)";
        return null;
    }

    public record FinCheck(String status, List<String> blocks, List<String> reviews, List<String> notes) { }

    /** 재무 필수 검증: CONFIRMED / UNCONFIRMED / BLOCK. */
    public static FinCheck financial(Fundamentals f, LocalDate asOf) {
        List<String> blocks = new ArrayList<>(), reviews = new ArrayList<>(), notes = new ArrayList<>();
        if (f == null) return new FinCheck("UNCONFIRMED", blocks, reviews, List.of("재무제표 미확보"));
        boolean hasPl = f.revenue != null || f.opIncome != null;
        boolean hasBs = f.totalEquity != null || f.totalLiab != null;
        if (!hasPl || !hasBs) notes.add("손익(" + (hasPl ? "있음" : "없음") + ")·재무상태(" + (hasBs ? "있음" : "없음") + ") 일부 누락");
        if (f.periodEnd != null) {
            try {
                LocalDate pe = LocalDate.parse(f.periodEnd);
                if (pe.isBefore(asOf.minusDays(270))) notes.add("최근 재무 기준일 " + f.periodEnd + " — 270일 초과(지연)");
            } catch (Exception ignored) { }
        }
        if (f.capitalImpaired()) blocks.add("자본잠식(자본총계 < 자본금)");
        Double cr = f.currentRatio();
        // 장부 비율만으로 '심각한 부채'를 판정하지 않는다: 위기 징후(영업적자·영업현금흐름 음수·유동비율<0.8)가 동반될 때만 차단
        List<String> distress = new ArrayList<>();
        if (f.opIncome != null && f.opIncome <= 0) distress.add("영업적자");
        if (f.ocf != null && f.ocf <= 0) distress.add("영업현금흐름 음수");
        if (cr != null && cr < 0.8) distress.add("유동비율 " + Num.f2(cr));
        Double dr = f.debtRatio();
        String lev = null;
        if (f.totalEquity != null && f.totalEquity <= 0 && !f.capitalImpaired()) lev = "자본총계 0 이하";
        else if (dr != null && f.currentAssets != null && dr > 4.0) lev = "부채비율 " + Math.round(dr * 100) + "% > 400%";
        if (lev != null) {
            if (!distress.isEmpty()) blocks.add(lev + " + " + String.join("·", distress) + "(부채·유동성 위기 의심)");
            else notes.add(lev + " — 영업이익·현금흐름 양호, 자사주 매입 등 자본 정책 가능성(차단 안 함, app-8)");
        }
        if (cr != null && cr < 0.5) reviews.add("유동비율 " + Num.f2(cr) + " < 0.5");
        Double ry = f.revenueYoY();
        if (ry != null && ry <= -0.2 && f.opIncome != null && f.opIncome < 0) blocks.add("매출 " + Num.pct(ry) + " & 영업적자(구조적 실적 악화 의심)");
        String st;
        if (!blocks.isEmpty()) st = "BLOCK";
        else if (!hasPl || !hasBs || notes.stream().anyMatch(n -> n.contains("지연"))) st = "UNCONFIRMED";
        else st = "CONFIRMED";
        return new FinCheck(st, blocks, reviews, notes);
    }

    /** 건전성 판정: 부채비율·유동비율·영업현금흐름. 복수 양호=1, 혼재=0.5, 악화 명확=0, 자료 없음=null. */
    public static Object[] health(Fundamentals f) {
        if (f == null) return new Object[]{null, "재무 자료 없음"};
        int good = 0, bad = 0, n = 0;
        List<String> obs = new ArrayList<>();
        Double dr = f.debtRatio();
        boolean finLike = f.currentAssets == null && dr != null && dr > 3.0;
        if (dr != null && !finLike) {
            n++;
            obs.add("부채비율 " + Math.round(dr * 100) + "%");
            if (dr <= 1.0) good++; else if (dr > 2.0) bad++;
        } else if (finLike) obs.add("부채비율 " + Math.round(dr * 100) + "%(유동 구분 없음 — 금융업 가능, 판정 제외)");
        Double cr = f.currentRatio();
        if (cr != null) {
            n++;
            obs.add("유동비율 " + Num.f2(cr));
            if (cr >= 1.0) good++; else if (cr < 0.8) bad++;
        }
        if (f.ocf != null) {
            n++;
            obs.add("영업현금흐름 " + (f.ocf > 0 ? "+" : "") + String.format("%.3g", f.ocf) + (f.ocfPeriod != null ? "(" + f.ocfPeriod + ")" : ""));
            if (f.ocf > 0) good++; else bad++;
        }
        if (n == 0) return new Object[]{null, String.join(", ", obs).isEmpty() ? "건전성 지표 없음" : String.join(", ", obs)};
        Double g;
        if (n == 1) g = good == 1 ? 0.5 : 0.0;          // 단일 확인값은 '복수 양호' 아님
        else if (good >= 2 && bad == 0) g = 1.0;
        else if (bad >= 2 || (bad >= 1 && good == 0)) g = 0.0;
        else g = 0.5;
        return new Object[]{g, String.join(", ", obs)};
    }

    /** 수익·성장 판정: 매출 YoY, 영업이익 YoY(흑자 전환 포함). */
    public static Object[] growth(Fundamentals f) {
        if (f == null) return new Object[]{null, "재무 자료 없음"};
        int good = 0, bad = 0, n = 0;
        List<String> obs = new ArrayList<>();
        Double ry = f.revenueYoY();
        if (ry != null) {
            n++;
            obs.add("매출 YoY " + Num.pct(ry));
            if (ry > 0) good++; else bad++;
        }
        if (f.opIncome != null && f.opIncomePrev != null) {
            n++;
            if (f.opIncomePrev > 0) {
                double oy = f.opIncome / f.opIncomePrev - 1;
                obs.add("영업이익 YoY " + Num.pct(oy));
                if (f.opIncome > 0 && oy > 0) good++; else bad++;
            } else {
                obs.add(f.opIncome > 0 ? "영업이익 흑자전환" : "영업적자 지속");
                if (f.opIncome > 0) good++; else bad++;
            }
        }
        if (n == 0) return new Object[]{null, "매출·영업이익 비교값 없음"};
        Double g;
        if (n == 1) g = good == 1 ? 0.5 : 0.0;
        else if (good == 2) g = 1.0;
        else if (bad == 2) g = 0.0;
        else g = 0.5;
        if (f.period != null) obs.add("기간 " + f.period);
        return new Object[]{g, String.join(", ", obs)};
    }

    public static Map<String, Object> mandatoryRow(String name, String status, String detail, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("status", status);
        m.put("detail", detail);
        m.put("source", source);
        return m;
    }
}
