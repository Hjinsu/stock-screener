package screener;

import screener.model.SourceStatus;
import screener.source.DartSource;
import screener.source.KrxSource;
import screener.source.NaverSource;
import screener.source.UsSources;
import screener.util.Http;
import screener.util.Log;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** 공급원별 소량 요청으로 접근 가능 여부·응답 건수를 확인한다(`check-sources`). 키 값은 출력하지 않는다. */
public final class SourceCheck {
    private SourceCheck() { }

    public static List<Map<String, Object>> run(Config cfg) {
        Http http = new Http(cfg.cacheDir.resolve("check"), cfg.userAgent);
        SourceStatus.Registry reg = new SourceStatus.Registry();
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate d = LocalDate.now().minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.minusDays(1);
        final LocalDate day = d;
        KrxSource krx = new KrxSource(http, cfg.krxKey, reg);
        DartSource dart = new DartSource(http, cfg.dartKey, reg);
        NaverSource naver = new NaverSource(http, reg);
        UsSources.NasdaqTrader nt = new UsSources.NasdaqTrader(http, reg);
        UsSources.Sec sec = new UsSources.Sec(http, cfg.secUserAgent, reg);
        UsSources.Yahoo yahoo = new UsSources.Yahoo(http, reg);
        UsSources.Tiingo tiingo = new UsSources.Tiingo(http, cfg.tiingoKey, reg);
        UsSources.Cboe cboe = new UsSources.Cboe(http, reg);
        UsSources.Fmp fmp = new UsSources.Fmp(http, cfg.fmpKey, 1, reg);
        UsSources.Finra finra = new UsSources.Finra(http, reg);

        check(out, "KRX 유가증권 일별매매정보", "KRX_API_KEY", cfg.krxKey != null, () -> {
            LocalDate x = day;
            for (int i = 0; i < 7; i++, x = x.minusDays(1)) {
                int n = krx.daily("STK", x).size();
                if (n > 0) return n + "종목 (" + x + ")";
            }
            return "최근 7일 자료 없음";
        });
        check(out, "KRX 코스닥 일별매매정보", "KRX_API_KEY", cfg.krxKey != null, () -> {
            LocalDate x = day;
            for (int i = 0; i < 7; i++, x = x.minusDays(1)) {
                int n = krx.daily("KSQ", x).size();
                if (n > 0) return n + "종목 (" + x + ")";
            }
            return "최근 7일 자료 없음";
        });
        check(out, "OpenDART 공시검색(삼성전자)", "DART_API_KEY", cfg.dartKey != null, () -> {
            var c = dart.disclosures("00126380", day);
            if (!c.ok) throw new IllegalStateException(c.error);
            return c.scanned + "건/90일";
        });
        check(out, "Naver 투자자 동향(005930)", null, true, () -> naver.trend("005930", null, 20).size() + "일");
        check(out, "Naver KOSPI 지수 일봉", null, true, () -> naver.indexChart("KOSPI", 30).size() + "봉");
        check(out, "NasdaqTrader 상장목록", null, true, () -> nt.all().size() + "건");
        check(out, "SEC company_tickers", "SEC_USER_AGENT", sec.enabled(), () -> sec.tickers().size() + "건");
        check(out, "SEC XBRL frames(발행주식수)", "SEC_USER_AGENT", sec.enabled(), () -> {
            int y = day.getYear();
            int n = sec.frame("dei", "EntityCommonStockSharesOutstanding", "shares", "CY" + (y - 1) + "Q4I").size();
            if (n == 0) throw new IllegalStateException("0건");
            return n + "개 기업";
        });
        check(out, "Yahoo 일봉(^GSPC)", null, true, () -> yahoo.chart("^GSPC", "1mo", java.time.Duration.ofMinutes(10)).size() + "봉");
        check(out, "Cboe VIX CSV", null, true, () -> cboe.history("VIX").size() + "일");
        check(out, "Cboe VIX3M CSV", null, true, () -> cboe.history("VIX3M").size() + "일");
        check(out, "Tiingo 일봉(AAPL)", "TIINGO_API_KEY(선택)", tiingo.enabled(), () -> tiingo.daily("AAPL", day.minusDays(10)).size() + "봉");
        check(out, "FMP quote(AAPL 시총)", "FMP_API_KEY(선택)", fmp.available(), () -> "시총 " + screener.util.Num.usd(fmp.marketCap("AAPL")));
        check(out, "FINRA ATS 주간(AAPL)", null, true, () -> {
            Object[] w = finra.weekly("AAPL", day);
            if (w == null) throw new IllegalStateException("최근 6주 자료 없음");
            return "주간 " + w[0] + ", 공개 " + w[3];
        });
        screener.source.NaverNewsSource news = new screener.source.NaverNewsSource(http, cfg.naverId, cfg.naverSecret, reg);
        check(out, "NAVER API HUB 뉴스 검색", "NAVER_CLIENT_ID/SECRET", news.enabled(), () -> news.page("코스피", 1, 5).size() + "건(최신순)");
        check(out, "한국은행 ECOS 환율(731Y001)", "ECOS_API_KEY(선택)", cfg.ecosKey != null, () -> {
            String url = "https://ecos.bok.or.kr/api/StatisticSearch/" + cfg.ecosKey + "/json/kr/1/10/731Y001/D/"
                    + day.minusDays(14).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "/" + day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "/0000001";
            String b = http.getText(reg.get("ECOS"), url, null, java.time.Duration.ofMinutes(10));
            if (!b.contains("StatisticSearch")) throw new IllegalStateException(Log.redact(b.length() > 120 ? b.substring(0, 120) : b));
            return "원/달러 매매기준율 응답 정상";
        });
        Map<String, Object> cnn = new LinkedHashMap<>();
        cnn.put("source", "CNN Fear & Greed");
        cnn.put("status", "미사용");
        cnn.put("detail", "자동 요청 차단(HTTP 418) 확인 — 차단 우회는 하지 않음. MSI 교차검증 미사용");
        out.add(cnn);
        Map<String, Object> scr = new LinkedHashMap<>();
        scr.put("source", "Nasdaq 스크리너(api.nasdaq.com)");
        scr.put("status", "미사용");
        scr.put("detail", "브라우저 위장 없이는 응답하지 않음 — 시총은 SEC 발행주식수×종가로 산출");
        out.add(scr);
        return out;
    }

    private static void check(List<Map<String, Object>> out, String name, String key, boolean enabled, Callable<String> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", name);
        if (!enabled) {
            m.put("status", "건너뜀");
            m.put("detail", key + " 미설정");
            out.add(m);
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            m.put("status", "OK");
            m.put("detail", c.call());
        } catch (Exception e) {
            m.put("status", "실패");
            m.put("detail", Log.redact(e.getMessage()));
        }
        m.put("ms", System.currentTimeMillis() - t0);
        out.add(m);
    }
}
