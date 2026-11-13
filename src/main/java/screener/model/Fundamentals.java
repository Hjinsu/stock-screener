package screener.model;

/** 공시 원천 재무 요약. 없는 값은 null(추정하지 않음). 전년 동기 값은 *Prev. */
public final class Fundamentals {
    public String source;
    public String period;        // 예: 2026 반기(11012), CY2026Q2
    public String periodEnd;     // 기준일
    public String filedOrReceipt;
    public Double revenue, revenuePrev;
    public Double opIncome, opIncomePrev;
    public Double netIncome, netIncomePrev;
    public Double totalLiab, totalEquity, capitalStock;
    public Double currentAssets, currentLiab;
    public Double ocf;           // 영업현금흐름(연간)
    public String ocfPeriod;
    public Double annualNetIncome; // 밸류에이션용(최근 연간 또는 분기×4 근사 여부는 note)
    public String earningsNote;

    public Double debtRatio() { return totalLiab != null && totalEquity != null && totalEquity > 0 ? totalLiab / totalEquity : null; }
    public Double currentRatio() { return currentAssets != null && currentLiab != null && currentLiab > 0 ? currentAssets / currentLiab : null; }
    public Double revenueYoY() { return revenue != null && revenuePrev != null && revenuePrev > 0 ? revenue / revenuePrev - 1 : null; }
    public Double opIncomeYoY() {
        return opIncome != null && opIncomePrev != null && opIncomePrev > 0 ? opIncome / opIncomePrev - 1 : null;
    }
    public boolean capitalImpaired() { return totalEquity != null && capitalStock != null && totalEquity < capitalStock; }
}
