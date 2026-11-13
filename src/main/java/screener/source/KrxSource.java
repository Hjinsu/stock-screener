package screener.source;

import screener.model.SourceStatus;
import screener.util.Http;
import screener.util.Json;
import screener.util.Num;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * KRX 정보데이터시스템 OpenAPI (https://openapi.krx.co.kr). 무료 API 키(AUTH_KEY 헤더) + 서비스별 이용 신청 필요.
 * 검증된 서비스: 유가증권 일별매매정보(stk_bydd_trd), 코스닥 일별매매정보(ksq_bydd_trd), KOSPI/KOSDAQ 지수 일별시세.
 * 한 번 호출로 해당 일자 전 종목의 OHLC·거래량·거래대금·시가총액·상장주식수를 받는다.
 */
public final class KrxSource {
    public static final String NAME = "KRX OpenAPI";
    private static final String BASE = "https://data-dbg.krx.co.kr/svc/apis/";
    private static final DateTimeFormatter D = DateTimeFormatter.BASIC_ISO_DATE;
    private final Http http;
    private final String key;
    private final SourceStatus st;

    public KrxSource(Http http, String key, SourceStatus.Registry reg) {
        this.http = http;
        this.key = key;
        this.st = reg.get(NAME);
        http.rateLimit("data-dbg.krx.co.kr", 350);
        if (key == null) { st.disabled = true; st.note = "KRX_API_KEY 미설정"; }
    }

    public boolean enabled() { return key != null; }

    /** market: STK(유가증권) | KSQ(코스닥). 과거 일자는 장기 캐시, 최근 3일은 1시간 캐시. */
    public List<Map<String, Object>> daily(String market, LocalDate date) throws Exception {
        if (key == null) throw new IllegalStateException("KRX_API_KEY 미설정");
        String path = "STK".equals(market) ? "sto/stk_bydd_trd" : "sto/ksq_bydd_trd";
        Duration ttl = date.isBefore(LocalDate.now().minusDays(3)) ? Duration.ofDays(3650) : Duration.ofHours(1);
        String body = http.getText(st, BASE + path + "?basDd=" + date.format(D), Map.of("AUTH_KEY", key), ttl);
        return rows(body);
    }

    /** 지수 일별시세. idxMarket: kospi | kosdaq */
    public Double indexClose(String idxMarket, String idxName, LocalDate date) throws Exception {
        if (key == null) return null;
        String body = http.getText(st, BASE + "idx/" + idxMarket + "_dd_trd?basDd=" + date.format(D), Map.of("AUTH_KEY", key), Duration.ofHours(6));
        for (Map<String, Object> r : rows(body)) if (idxName.equals(Json.str(r, "IDX_NM"))) return Num.parse(Json.str(r, "CLSPRC_IDX"));
        return null;
    }

    static List<Map<String, Object>> rows(String body) {
        Map<String, Object> o = Json.obj(Json.parse(body));
        List<Map<String, Object>> out = new ArrayList<>();
        if (o == null) return out;
        if (o.containsKey("respCode")) throw new IllegalStateException("KRX 응답 오류 " + o.get("respCode") + " " + o.get("respMsg"));
        List<Object> a = Json.arr(o.get("OutBlock_1"));
        if (a != null) for (Object x : a) out.add(Json.obj(x));
        return out;
    }
}
