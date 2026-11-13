package screener.source;

import screener.model.Bar;
import screener.model.SourceStatus;
import screener.util.Http;
import screener.util.Json;
import screener.util.Num;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 미국 공급원 어댑터 모음. 각 클래스는 자기 SourceStatus에만 실패를 기록한다(소스별 격리). */
public final class UsSources {
    private UsSources() { }

    /** Nasdaq Trader Symbol Directory (공식, 키 불필요, 매 영업일 갱신). ETF·테스트 종목·재무상태 플래그 포함. */
    public static final class NasdaqTrader {
        public static final String NAME = "NasdaqTrader 상장목록";
        private final Http http;
        private final SourceStatus st;

        public NasdaqTrader(Http http, SourceStatus.Registry reg) { this.http = http; this.st = reg.get(NAME); }

        public record Listing(String symbol, String name, String exchange, boolean etf, boolean test, String finStatus) { }

        public List<Listing> all() throws Exception {
            List<Listing> out = new ArrayList<>();
            String a = http.getText(st, "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt", null, Duration.ofHours(12));
            for (String line : a.split("\r?\n")) {
                String[] f = line.split("\\|", -1);
                if (f.length < 8 || f[0].equals("Symbol") || line.startsWith("File Creation")) continue;
                out.add(new Listing(f[0].trim(), f[1].trim(), "NASDAQ", "Y".equals(f[6]), "Y".equals(f[3]), f[4].trim()));
            }
            String b = http.getText(st, "https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt", null, Duration.ofHours(12));
            for (String line : b.split("\r?\n")) {
                String[] f = line.split("\\|", -1);
                if (f.length < 8 || f[0].equals("ACT Symbol") || line.startsWith("File Creation")) continue;
                String ex;
                switch (f[2]) { case "N": ex = "NYSE"; break; case "A": ex = "NYSE American"; break; case "P": ex = "NYSE Arca"; break; case "Z": ex = "Cboe BZX"; break; case "V": ex = "IEX"; break; default: ex = f[2]; }
                out.add(new Listing(f[0].trim(), f[1].trim(), ex, "Y".equals(f[4]), "Y".equals(f[6]), ""));
            }
            st.items = out.size();
            return out;
        }
    }

    /** SEC EDGAR (공식, 키 불필요, User-Agent에 연락처 필수, 초당 10회 이하). */
    public static final class Sec {
        public static final String NAME = "SEC EDGAR";
        private final Http http;
        private final String ua;
        private final SourceStatus st;

        public Sec(Http http, String ua, SourceStatus.Registry reg) {
            this.http = http;
            this.ua = ua;
            this.st = reg.get(NAME);
            http.rateLimit("www.sec.gov", 150);
            http.rateLimit("data.sec.gov", 150);
            if (ua == null) { st.disabled = true; st.note = "SEC_USER_AGENT 미설정 — SEC 정책상 요청 불가"; }
        }

        public boolean enabled() { return ua != null; }

        private Map<String, String> h() { return Map.of("User-Agent", ua); }

        /** 티커(대문자, '.'→'-') → CIK */
        public Map<String, String> tickers() throws Exception {
            Map<String, Object> o = Json.obj(Json.parse(http.getText(st, "https://www.sec.gov/files/company_tickers.json", h(), Duration.ofDays(3))));
            Map<String, String> m = new HashMap<>();
            for (Object v : o.values()) {
                Map<String, Object> r = Json.obj(v);
                m.put(Json.str(r, "ticker").toUpperCase(), String.valueOf(((Number) r.get("cik_str")).longValue()));
            }
            st.items = m.size();
            return m;
        }

        /** frames API: CIK → {val, end, accn}. 기간에 자료가 없으면(404) 빈 맵. */
        public Map<String, Object[]> frame(String taxonomy, String tag, String unit, String period) {
            String url = "https://data.sec.gov/api/xbrl/frames/" + taxonomy + "/" + tag + "/" + unit + "/" + period + ".json";
            Map<String, Object[]> m = new HashMap<>();
            try {
                Map<String, Object> o = Json.obj(Json.parse(http.getText(st, url, h(), Duration.ofHours(20))));
                for (Object x : Json.arr(o.get("data"))) {
                    Map<String, Object> r = Json.obj(x);
                    m.put(String.valueOf(((Number) r.get("cik")).longValue()), new Object[]{Json.num(r, "val"), Json.str(r, "end"), Json.str(r, "accn"), Json.str(r, "loc")});
                }
            } catch (Http.HttpError e) {
                if (e.status != 404) st.note = "frames " + tag + " " + period + ": " + e.getMessage();
            } catch (Exception e) {
                st.note = "frames " + tag + " " + period + ": " + e.getMessage();
            }
            return m;
        }

        public Map<String, Object> submissions(String cik) throws Exception {
            String padded = String.format("%010d", Long.parseLong(cik));
            return Json.obj(Json.parse(http.getText(st, "https://data.sec.gov/submissions/CIK" + padded + ".json", h(), Duration.ofHours(20))));
        }
    }

    /** Yahoo Finance chart API (비공식, 키 불필요). 응답 형식 변경·차단 가능. 분할 조정 종가 사용. */
    public static final class Yahoo {
        public static final String NAME = "Yahoo Finance(비공식)";
        private final Http http;
        private final SourceStatus st;

        public Yahoo(Http http, SourceStatus.Registry reg) {
            this.http = http;
            this.st = reg.get(NAME);
            http.rateLimit("query1.finance.yahoo.com", 400);
        }

        public static String sym(String s) { return s.replace('.', '-').replace('/', '-'); }

        public List<Bar> chart(String symbol, String range, Duration ttl) throws Exception {
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + java.net.URLEncoder.encode(sym(symbol), "UTF-8") + "?range=" + range + "&interval=1d";
            Map<String, Object> o = Json.obj(Json.parse(http.getText(st, url, null, ttl)));
            Map<String, Object> ch = Json.obj(o.get("chart"));
            List<Object> res = Json.arr(ch.get("result"));
            if (res == null || res.isEmpty()) throw new IllegalStateException("결과 없음: " + Json.str(Json.obj(ch.get("error")), "description"));
            Map<String, Object> r = Json.obj(res.get(0));
            Map<String, Object> meta = Json.obj(r.get("meta"));
            ZoneId tz = ZoneId.of(meta.get("exchangeTimezoneName") == null ? "America/New_York" : (String) meta.get("exchangeTimezoneName"));
            List<Object> ts = Json.arr(r.get("timestamp"));
            List<Bar> out = new ArrayList<>();
            if (ts == null) return out;
            Map<String, Object> q = Json.obj(Json.arr(Json.obj(r.get("indicators")).get("quote")).get(0));
            List<Object> o1 = Json.arr(q.get("open")), h1 = Json.arr(q.get("high")), l1 = Json.arr(q.get("low")), c1 = Json.arr(q.get("close")), v1 = Json.arr(q.get("volume"));
            for (int i = 0; i < ts.size(); i++) {
                if (c1.get(i) == null || o1.get(i) == null || h1.get(i) == null || l1.get(i) == null) continue;
                LocalDate d = Instant.ofEpochSecond(((Number) ts.get(i)).longValue()).atZone(tz).toLocalDate();
                double v = v1.get(i) == null ? 0 : ((Number) v1.get(i)).doubleValue();
                Bar b = Bar.of(d, ((Number) o1.get(i)).doubleValue(), ((Number) h1.get(i)).doubleValue(), ((Number) l1.get(i)).doubleValue(), ((Number) c1.get(i)).doubleValue(), v);
                if (!out.isEmpty() && out.get(out.size() - 1).date().equals(d)) out.set(out.size() - 1, b); else out.add(b);
            }
            return out;
        }

        /** 다종목 최근 종가(spark, 최대 20종목/호출). Tiingo 키가 없을 때 예비 시총 산출용. */
        public Map<String, Double> spark(List<String> symbols) throws Exception {
            List<String> ys = new ArrayList<>();
            for (String s : symbols) ys.add(sym(s));
            String url = "https://query1.finance.yahoo.com/v7/finance/spark?symbols=" + java.net.URLEncoder.encode(String.join(",", ys), "UTF-8") + "&range=5d&interval=1d";
            Map<String, Object> o = Json.obj(Json.parse(http.getText(st, url, null, Duration.ofHours(6))));
            Map<String, Double> m = new HashMap<>();
            List<Object> res = Json.arr(Json.obj(o.get("spark")).get("result"));
            if (res == null) return m;
            for (Object x : res) {
                Map<String, Object> r = Json.obj(x);
                List<Object> resp = Json.arr(r.get("response"));
                if (resp == null || resp.isEmpty()) continue;
                Map<String, Object> meta = Json.obj(Json.obj(resp.get(0)).get("meta"));
                Double p = Json.num(meta, "regularMarketPrice");
                if (p != null) m.put(Json.str(r, "symbol"), p);
            }
            return m;
        }
    }

    /** Tiingo (무료 키). IEX 전 종목 최근가 1회 호출, 일봉 예비 공급원. 키는 Authorization 헤더로 전달. */
    public static final class Tiingo {
        public static final String NAME = "Tiingo";
        private final Http http;
        private final String key;
        private final SourceStatus st;

        public Tiingo(Http http, String key, SourceStatus.Registry reg) {
            this.http = http;
            this.key = key;
            this.st = reg.get(NAME);
            http.rateLimit("api.tiingo.com", 1000);
            if (key == null) { st.disabled = true; st.note = "TIINGO_API_KEY 미설정(선택)"; }
        }

        public boolean enabled() { return key != null; }

        /** 티커 → {가격, 시각(ISO)} */
        public Map<String, Object[]> iexAll() throws Exception {
            List<Object> a = Json.arr(Json.parse(http.getText(st, "https://api.tiingo.com/iex/", Map.of("Authorization", "Token " + key), Duration.ofHours(6))));
            Map<String, Object[]> m = new HashMap<>();
            for (Object x : a) {
                Map<String, Object> r = Json.obj(x);
                Double p = Json.num(r, "tngoLast");
                if (p == null) p = Json.num(r, "prevClose");
                if (p == null) continue;
                m.put(Json.str(r, "ticker").toUpperCase(), new Object[]{p, Json.str(r, "timestamp")});
            }
            st.items = m.size();
            return m;
        }

        public List<Bar> daily(String symbol, LocalDate from) throws Exception {
            String url = "https://api.tiingo.com/tiingo/daily/" + Yahoo.sym(symbol).toLowerCase() + "/prices?startDate=" + from;
            List<Object> a = Json.arr(Json.parse(http.getText(st, url, Map.of("Authorization", "Token " + key), Duration.ofHours(12))));
            List<Bar> out = new ArrayList<>();
            for (Object x : a) {
                Map<String, Object> r = Json.obj(x);
                // 분할 조정 일관성: adj* 는 배당까지 조정되므로 splitFactor 누적 대신 원값을 쓰지 않고 adj 계열 사용
                out.add(Bar.of(LocalDate.parse(Json.str(r, "date").substring(0, 10)), Json.num(r, "adjOpen"), Json.num(r, "adjHigh"), Json.num(r, "adjLow"),
                        Json.num(r, "adjClose"), Json.num(r, "adjVolume")));
            }
            return out;
        }
    }

    /** Cboe 지수 일별 CSV (공식, 키 불필요). VIX, VIX3M. */
    public static final class Cboe {
        public static final String NAME = "Cboe 지수 CSV";
        private final Http http;
        private final SourceStatus st;

        public Cboe(Http http, SourceStatus.Registry reg) { this.http = http; this.st = reg.get(NAME); }

        public List<Bar> history(String idx) throws Exception {
            String body = http.getText(st, "https://cdn.cboe.com/api/global/us_indices/daily_prices/" + idx + "_History.csv", null, Duration.ofHours(6));
            DateTimeFormatter f = DateTimeFormatter.ofPattern("MM/dd/yyyy");
            List<Bar> out = new ArrayList<>();
            for (String line : body.split("\r?\n")) {
                String[] p = line.split(",");
                if (p.length < 5 || p[0].startsWith("DATE")) continue;
                try {
                    out.add(Bar.of(LocalDate.parse(p[0].trim(), f), Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]), 0));
                } catch (Exception ignored) { }
            }
            return out;
        }
    }

    /** Financial Modeling Prep (무료 키, 일 250회). 시총 보조(ADR·다중 클래스 등 SEC 주식수로 산출 불가 종목)에만 예산 내 사용. */
    public static final class Fmp {
        public static final String NAME = "FMP(보조 시총)";
        private final Http http;
        private final String key;
        private final SourceStatus st;
        private int budget;

        public Fmp(Http http, String key, int budget, SourceStatus.Registry reg) {
            this.http = http;
            this.key = key;
            this.budget = budget;
            this.st = reg.get(NAME);
            http.rateLimit("financialmodelingprep.com", 300);
            http.noRetryOn429("financialmodelingprep.com");
            if (key == null) { st.disabled = true; st.note = "FMP_API_KEY 미설정(선택)"; }
        }

        public boolean available() { return key != null && budget > 0; }

        public Double marketCap(String symbol) throws Exception {
            if (!available()) return null;
            budget--;
            List<Object> a;
            try {
                a = Json.arr(Json.parse(http.getText(st, "https://financialmodelingprep.com/stable/quote?symbol=" + Yahoo.sym(symbol) + "&apikey=" + key, null, Duration.ofHours(20))));
            } catch (Http.HttpError e) {
                if (e.status == 429 || e.status == 402) { budget = 0; st.note = "한도 초과/권한 없음(HTTP " + e.status + ") — 이번 실행에서 FMP 중단"; }
                throw e;
            }
            if (a == null || a.isEmpty()) return null;
            return Json.num(Json.obj(a.get(0)), "marketCap");
        }
    }

    /** FINRA OTC Transparency 주간 ATS 집계(공식, 키 불필요, 약 2~4주 지연). 간접 자료 — 매수 주체·방향 정보 없음. */
    public static final class Finra {
        public static final String NAME = "FINRA ATS(간접)";
        private final Http http;
        private final SourceStatus st;
        private final Map<String, Object[]> cache = new HashMap<>();
        private LocalDate latestWeek;

        public Finra(Http http, SourceStatus.Registry reg) {
            this.http = http;
            this.st = reg.get(NAME);
            http.rateLimit("api.finra.org", 300);
        }

        /** 반환 {주간 시작일, ATS 주식수, 거래건수} 또는 null */
        public Object[] weekly(String symbol, LocalDate asOf) {
            if (cache.containsKey(symbol)) return cache.get(symbol);
            Object[] res = null;
            LocalDate monday = asOf.minusDays(asOf.getDayOfWeek().getValue() - 1);
            List<LocalDate> weeks = new ArrayList<>();
            if (latestWeek != null) weeks.add(latestWeek);
            for (int k = 2; k <= 6; k++) weeks.add(monday.minusWeeks(k));
            for (LocalDate w : weeks) {
                try {
                    String body = "{\"limit\":5,\"compareFilters\":[" +
                            "{\"compareType\":\"EQUAL\",\"fieldName\":\"issueSymbolIdentifier\",\"fieldValue\":\"" + symbol.replace("-", ".") + "\"}," +
                            "{\"compareType\":\"EQUAL\",\"fieldName\":\"weekStartDate\",\"fieldValue\":\"" + w + "\"}," +
                            "{\"compareType\":\"EQUAL\",\"fieldName\":\"tierIdentifier\",\"fieldValue\":\"T1\"}," +
                            "{\"compareType\":\"EQUAL\",\"fieldName\":\"summaryTypeCode\",\"fieldValue\":\"ATS_W_SMBL\"}]}";
                    String r = http.postJson(st, "https://api.finra.org/data/group/otcMarket/name/weeklySummary",
                            Map.of("Accept", "application/json", "Content-Type", "application/json"), body, Duration.ofHours(20));
                    if (r.isBlank()) continue;
                    List<Object> a = Json.arr(Json.parse(r));
                    if (a == null || a.isEmpty()) continue;
                    Map<String, Object> m = Json.obj(a.get(0));
                    res = new Object[]{w, Json.num(m, "totalWeeklyShareQuantity"), Json.num(m, "totalWeeklyTradeCount"), Json.str(m, "initialPublishedDate")};
                    latestWeek = w;
                    break;
                } catch (Exception e) {
                    // 다음 주차 시도
                }
            }
            cache.put(symbol, res);
            return res;
        }
    }

    public static Double num(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : o == null ? null : Num.parse(o.toString()); }
}
