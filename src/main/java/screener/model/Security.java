package screener.model;

/** 공급원에서 자동 수집한 상장 종목. 사용자가 입력하지 않는다. */
public final class Security {
    public final String market;   // KR | US
    public final String symbol;   // KR: 6자리 단축코드, US: 티커
    public String name;
    public String board;          // KOSPI/KOSDAQ/NASDAQ/NYSE/...
    public Double marketCap;      // 현지 통화
    public String mcapSource;
    public boolean mcapApprox;
    public Double sharesOut;
    public String sector;         // KR: Naver industryCode, US: SEC SIC 2자리 그룹
    public String sectorName;
    public String cik;            // US
    public String corpCode;       // KR DART
    public boolean etf, preferred, spac, nonCommon, testIssue;
    public String statusFlag;     // 관리/거래정지/재무상태 문제 등 (null=확인된 문제 없음)
    public boolean statusUnknown; // 상태 정보를 공급원이 제공하지 않음
    public String dupOf;          // 동일 기업 다른 클래스로 대체된 경우 대표 티커

    public Security(String market, String symbol, String name) {
        this.market = market;
        this.symbol = symbol;
        this.name = name;
    }

    /** 명세상 유니버스 제외 사유. null이면 제외 대상 아님. */
    public String typeExclusion() {
        if (dupOf != null) return "동일 기업 다른 클래스(" + dupOf + " 유지)";
        if (etf) return "ETF/ETN";
        if (testIssue) return "테스트 종목";
        if (preferred) return "우선주";
        if (spac) return "SPAC";
        if (nonCommon) return "보통주 아님(워런트·유닛·채권 등)";
        if (statusFlag != null) return "상태 위험: " + statusFlag;
        return null;
    }

    public String key() { return market + ":" + symbol; }

    @Override public String toString() { return key() + "(" + name + ")"; }
}
