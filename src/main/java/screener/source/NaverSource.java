package screener.source;

import screener.model.Bar;
import screener.model.FlowDay;
import screener.model.SourceStatus;
import screener.util.Http;
import screener.util.Json;
import screener.util.Num;

import java.nio.charset.Charset;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 네이버 증권(비공식 공개 엔드포인트, 키 불필요). 문서화된 공식 API가 아니므로 응답 형식 변경·차단 가능성이 있다.
 * 사용 항목: 종목별 외국인·기관 순매매 수량 및 외국인 보유율(일별), 업종 코드, KOSPI/KOSDAQ 지수 일봉.
 */
public final class NaverSource {
    public static final String NAME = "Naver 증권(비공식)";
    private static final DateTimeFormatter D = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern ITEM = Pattern.compile("data=\"(\\d{8})\\|([\\d.]+)\\|([\\d.]+)\\|([\\d.]+)\\|([\\d.]+)\\|(\\d+)\"");
    private final Http http;
    private final SourceStatus st;

    public NaverSource(Http http, SourceStatus.Registry reg) {
        this.http = http;
        this.st = reg.get(NAME);
        http.rateLimit("m.stock.naver.com", 250);
        http.rateLimit("fchart.stock.naver.com", 250);
    }

    /** 최근 pageSize개(오름차순 아님) 투자자 동향. before=null 이면 최신부터. */
    public List<FlowDay> trend(String code, LocalDate before, int pageSize) throws Exception {
        String url = "https://m.stock.naver.com/api/stock/" + code + "/trend?pageSize=" + pageSize + (before == null ? "" : "&bizdate=" + before.format(D));
        String body = http.getText(st, url, null, before == null ? Duration.ofHours(6) : Duration.ofDays(30));
        List<Object> a = Json.arr(Json.parse(body));
        List<FlowDay> out = new ArrayList<>();
        if (a == null) return out;
        for (Object x : a) {
            Map<String, Object> m = Json.obj(x);
            String d = Json.str(m, "bizdate");
            Double close = Num.parse(Json.str(m, "closePrice"));
            if (d == null || close == null) continue;
            out.add(new FlowDay(LocalDate.parse(d, D), Num.parse(Json.str(m, "foreignerPureBuyQuant")), Num.parse(Json.str(m, "organPureBuyQuant")),
                    null, Num.parse(Json.str(m, "foreignerHoldRatio")), close));
        }
        return out;
    }

    public String industryCode(String code) throws Exception {
        String body = http.getText(st, "https://m.stock.naver.com/api/stock/" + code + "/integration", null, Duration.ofDays(30));
        Map<String, Object> m = Json.obj(Json.parse(body));
        Object ic = m == null ? null : m.get("industryCode");
        return ic == null ? null : String.valueOf(ic);
    }

    /** 지수 일봉(KOSPI/KOSDAQ). 거래대금 미제공. */
    public List<Bar> indexChart(String symbol, int count) throws Exception {
        String body = http.getText(st, "https://fchart.stock.naver.com/sise.nhn?symbol=" + symbol + "&timeframe=day&count=" + count + "&requestType=0",
                null, Duration.ofHours(3), Charset.forName("EUC-KR"));
        List<Bar> out = new ArrayList<>();
        Matcher mt = ITEM.matcher(body);
        while (mt.find()) {
            out.add(Bar.of(LocalDate.parse(mt.group(1), D), Double.parseDouble(mt.group(2)), Double.parseDouble(mt.group(3)),
                    Double.parseDouble(mt.group(4)), Double.parseDouble(mt.group(5)), Double.parseDouble(mt.group(6))));
        }
        return out;
    }
}
