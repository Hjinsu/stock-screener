package screener.provider;

import screener.model.Bar;
import screener.model.DisclosureCheck;
import screener.model.FlowDay;
import screener.model.Fundamentals;
import screener.model.Security;
import screener.model.SourceStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 시장별 데이터 공급자. 실데이터(KR/US)와 데모(합성)는 이 인터페이스로만 엔진에 연결되며,
 * 엔진은 공급자 종류와 무관하게 동일한 규칙을 적용한다.
 * 각 메서드는 소스 실패를 내부에서 격리하고 SourceStatus에 기록한 뒤 가능한 결과만 반환한다.
 */
public interface MarketDataProvider {
    String market();

    boolean isDemo();

    SourceStatus.Registry sources();

    /** 완료 거래일을 실제 자료로 확인. note에 확인 방법·근거를 기록. */
    LocalDate completedTradingDay(StringBuilder note) throws Exception;

    /** 상장 종목 전체 목록(예비 시총 포함). 사용자 입력 없이 공급원에서 수집. */
    List<Security> listUniverse(LocalDate asOf) throws Exception;

    /** 일봉(asOf 이하). 실패 종목은 결과에서 빠지고 SourceStatus에 기록된다. */
    Map<String, List<Bar>> bars(List<Security> secs, LocalDate asOf);

    /** 일봉 확보 후 시총 확정(미국: 발행주식수×완료일 종가). */
    void finalizeMarketCap(List<Security> secs, Map<String, List<Bar>> bars, LocalDate asOf);

    /** 벤치마크 지수(키: KOSPI/KOSDAQ 또는 SPX/NDX). */
    Map<String, List<Bar>> indexBars(LocalDate asOf);

    String benchmarkKey(Security s);

    /** 시장심리 보조 시계열(미국: VIX, VIX3M). */
    Map<String, List<Bar>> sentimentSeries(LocalDate asOf);

    boolean flowsSupported();

    Map<String, List<FlowDay>> flows(List<Security> secs, LocalDate asOf, int days);

    void enrichSectors(List<Security> secs);

    Map<String, Fundamentals> fundamentals(List<Security> secs, LocalDate asOf);

    DisclosureCheck disclosures(Security s, LocalDate asOf);

    /** 점수에 반영하지 않는 간접 자료(미국 13F/ATS/Form 4 등). */
    List<Map<String, Object>> indirectEvidence(Security s, List<Bar> bars, LocalDate asOf);

    /**
     * 모의 체결용 체결일 시가 관측. open=null 이면 시가 미확인(거래정지·무거래·자료 없음) — note에 사유.
     * dayVolume/dayValue 는 체결일 전체 집계로 사후 분석용이며 체결 판정에 사용하지 않는다.
     */
    record OpenObs(Double open, Double dayVolume, Double dayValue, String source, String note) { }

    default OpenObs openOn(Security s, LocalDate d, List<Bar> bars) {
        for (Bar b : bars) {
            if (!b.date().equals(d)) continue;
            if (b.open() <= 0) return new OpenObs(null, b.volume(), b.tradeValue(), priceSourceLabel(), "시가 없음(0 또는 결측)");
            return new OpenObs(b.open(), b.volume(), b.value() != null ? b.value() : null, priceSourceLabel(), null);
        }
        return new OpenObs(null, null, null, priceSourceLabel(), "체결일 일봉 없음(거래정지·상장 상태 확인 필요)");
    }

    /** 통합 평가용 환율(USD→KRW) 시계열. 키 "USDKRW". 미지원이면 빈 맵. */
    default Map<String, List<Bar>> fxSeries(LocalDate asOf) { return Map.of(); }

    default String fxSourceLabel() { return "-"; }

    /** 뉴스 심리 공급원(실데이터 공급자만). null 이면 미평가. */
    default screener.source.NaverNewsSource newsSource() { return null; }

    String priceSourceLabel();

    String flowSourceLabel();

    String fundamentalsSourceLabel();

    String disclosureSourceLabel();
}
