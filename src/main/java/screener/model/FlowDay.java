package screener.model;

import java.time.LocalDate;

/**
 * 투자자별 일별 순매수(수량). 금액은 수량×종가 근사(원천이 수량만 제공).
 * 기관 합계와 연기금 등 하위 분류는 합산하지 않는다(pension은 원천 미제공 시 null).
 */
public record FlowDay(LocalDate date, Double foreignQty, Double instQty, Double pensionQty,
                      Double foreignHoldPct, double close) {
}
