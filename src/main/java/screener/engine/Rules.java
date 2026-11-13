package screener.engine;

/**
 * 규칙 버전과 임계값. 원본 명세(01·02)의 필터·배점과 stock-skill(references)의 초기 계산 규칙,
 * 그리고 이 앱에서 추가한 운영 규칙을 구분해 버전으로 남긴다.
 */
public final class Rules {
    private Rules() { }

    public static final String MAS_VERSION = "MAS v1.0 (원본01 배점·필터 + skill accumulation.md 초기 판정 + app-rules v1.0)";
    public static final String OIS_VERSION = "OIS v1.0 (원본02 배점 + skill contrarian.md 초기 판정 + app-rules v1.0)";
    public static final String MSI_VERSION = "MSI v1.0 (원본02 가중치 + skill 252일 백분위 정규화 + app-rules v1.0)";

    /** 앱에서 추가한 규칙 목록(대시보드·README에 노출). */
    public static final String[] APP_RULES = {
        "app-1: 미국 시총 = SEC 공시 발행주식수(dei) × 완료일 종가(근사). 임계값 ±10% 이내는 필터 '미확정'.",
        "app-2: 미국 거래대금 = 종가×거래량 근사. 임계값 ±10% 이내는 필터 '미확정'.",
        "app-3: 한국 수급 금액 = 순매수 수량×당일 종가(근사). 연기금 세부는 원천 미제공으로 미평가.",
        "app-4: 최근 20일 상승일/하락일 거래량 배율 < 1 이면 거래량·거래대금·지속성 항목을 매집 근거로 보지 않음(0점, 반대 근거).",
        "app-5: 업종 비교는 유니버스 내 동일 업종 종목 중앙값(최소 4종목) — 업종지수 대체.",
        "app-6: 최근 61세션 중 상장주식수 1% 이상 변동 시 수익률 필터 '미확정'(KRX 원주가 비수정).",
        "app-7: 공시 위험 키워드 분류(차단/검토/참고) — 상세는 RiskRules.",
        "app-8: 재무 차단 — 한국 자본잠식(자본총계<자본금), 매출 -20% 이하 & 영업적자, 자본 음수·부채비율>400%는 영업적자·영업현금흐름 음수·유동비율<0.8 중 하나가 동반될 때만(장부 비율만으로 차단하지 않음).",
        "app-9: MSI 한국 변동성 = KOSPI 20일 실현변동성(VKOSPI 대체), 미국 파생 = VIX/VIX3M(풋콜·ETF 유출입 대체), 한국 수급 = 시총 상위 K종목 외국인·기관 순매수 합/거래대금(대체).",
        "app-10: 히스테리시스 — 단계 하한보다 3점 이상 하락(강화 ≤67, 고득점 ≤77, 매우강함 ≤87, 초기 ≤57) 시 하향, 추적 중 상향은 하한+2점 이상. 무효화 후 60거래일 추적 중에는 초기 의심(62점) 이상 재확인 시에만 복귀.",
        "app-12: 미국 시총 검증 — 20일 평균 거래대금/시총이 일 0.001% 미만이면 SEC 주식수 이상치로 보고 시총 미확인(순위·필터 미사용). FMP 확인 없는 ADR·해외 발행사(SEC loc≠US)도 시총 미확인.",
        "app-13: 뉴스 심리 — NAVER API HUB 최신순 검색(시장별 질의), 직전 거래일 마감~기준일 마감 창, 광고·기사 복제 제거, 키워드 규칙 7단계 분류, 언론사별 평균 후 평균, 독립 출처 20곳 미만 또는 창 전체를 덮은 질의가 하나도 없으면 미평가, 일부 질의는 창 후반부 표본임을 기록, 확정 결과는 재수집하지 않음(NEWS v1.0).",
        "app-14: 통합 평가 환율 — 한국은행 ECOS 원/미국달러 매매기준율 우선, 실패 시 Yahoo KRW=X(비공식) 대체·출처 표시.",
        "app-11: 알림 — 상태 전환 시에만 생성, 같은 기준일·유형 1회(UNIQUE), 같은 유형 10일 내 재알림 억제(위험·무효화 제외), 알림 단계에 이른 적 없는 관찰 후보의 무효화는 알림 없이 전환만 기록.",
    };

    // 원본 01: 매집 후보 유니버스
    public static double masMcap(String market) { return "KR".equals(market) ? 5e12 : 1e10; }
    public static double masValue(String market) { return "KR".equals(market) ? 1e10 : 2e7; }
    public static double pennyPrice(String market) { return "KR".equals(market) ? 1000 : 5; }
    public static final double MAS_RET20_MAX = 0.15;
    public static final double MAS_RET60_MAX = 0.30;
    public static final double APPROX_BOUNDARY = 0.10;

    /** MAS 단계(하한 이상·다음 하한 미만). */
    public static String masStage(double s) {
        if (s >= 90) return "VERY_HIGH";
        if (s >= 80) return "HIGH";
        if (s >= 70) return "STRONG";
        if (s >= 60) return "EARLY";
        if (s >= 50) return "WATCH";
        return "NONE";
    }

    public static int masRank(String st) {
        switch (st == null ? "" : st) {
            case "VERY_HIGH": return 5;
            case "HIGH": return 4;
            case "STRONG": return 3;
            case "EARLY": return 2;
            case "WATCH": return 1;
            default: return 0;
        }
    }

    public static double masLower(String st) {
        switch (st) {
            case "VERY_HIGH": return 90;
            case "HIGH": return 80;
            case "STRONG": return 70;
            case "EARLY": return 60;
            case "WATCH": return 50;
            default: return 0;
        }
    }

    public static String oisStage(double s) {
        if (s >= 80) return "PRIORITY";
        if (s >= 65) return "INTEREST";
        if (s >= 50) return "OBSERVE";
        return "HOLD";
    }

    public static String label(String code) {
        if (code == null) return "-";
        switch (code) {
            case "VERY_HIGH": return "매우 강한 복합 신호";
            case "HIGH": return "고득점 후보";
            case "STRONG": return "매집 의심 강화";
            case "EARLY": return "초기 매집 의심";
            case "WATCH": return "관찰 후보";
            case "NONE": return "후보 아님";
            case "WEAKENED": return "신호 약화";
            case "INVALIDATED": return "무효화";
            case "ENDED": return "추적 종료";
            case "PENDING": return "확인 대기";
            case "BLOCKED": return "등록 차단(위험)";
            case "PRIORITY": return "우선 매수 검토";
            case "INTEREST": return "관심종목";
            case "OBSERVE": return "관찰";
            case "HOLD": return "투자 보류";
            case "EXCLUDED_RISK": return "제외(중대 위험)";
            default: return code;
        }
    }

    public static String msiBand(int score) {
        if (score <= 15) return "극단적 공포";
        if (score <= 30) return "공포";
        if (score <= 45) return "약한 공포";
        if (score <= 60) return "중립";
        if (score <= 75) return "낙관";
        if (score <= 90) return "탐욕";
        return "극단적 환희";
    }

    public static String msiAction(int score) {
        if (score <= 15) return "적극적인 매수 기회 탐색";
        if (score <= 30) return "분할매수 준비";
        if (score <= 45) return "관찰";
        if (score <= 60) return "대기";
        if (score <= 75) return "보유자산 점검";
        if (score <= 90) return "차익실현 준비";
        return "적극적인 매도 검토";
    }
}
