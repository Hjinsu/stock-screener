package screener.model;

import java.time.LocalDate;

/**
 * 일봉. value=거래대금(공급원이 제공한 실제 값). valueApprox=true 이면 종가×거래량 근사치.
 * shares=상장주식수(KRX 제공 시) — 주식수 변동으로 수정주가 미반영 구간을 탐지하는 데 사용.
 */
public record Bar(LocalDate date, double open, double high, double low, double close, double volume,
                  Double value, boolean valueApprox, Double shares) {

    public static Bar of(LocalDate d, double o, double h, double l, double c, double v) {
        return new Bar(d, o, h, l, c, v, c * v, true, null);
    }

    public double tradeValue() { return value != null ? value : close * volume; }
}
