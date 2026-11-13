package screener;

import screener.util.Log;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 모든 설정은 환경 변수에서 읽는다. API 키는 로그 마스킹 대상으로 등록되며 DB/캐시에 저장하지 않는다. */
public final class Config {
    public final Path home;
    public final Path dbPath;
    public final Path cacheDir;
    public final Path logDir;
    public final String krxKey;
    public final String dartKey;
    public final String tiingoKey;
    public final String fmpKey;
    public final String fredKey;
    public final String secUserAgent;
    public final String naverId;
    public final String naverSecret;
    public final String ecosKey;
    public String kakaoRestKey;            // 카카오 REST API 키(선택 기능, 테스트에서 주입)
    public final String kakaoClientSecret; // 보안 › Client Secret 사용 시
    public final String kakaoRedirectUri;
    public final String kakaoLinkUrl;
    public final String dbUrl;       // 운영: jdbc:mariadb://127.0.0.1:3306/screener (없으면 SQLite)
    public final String dbUser;
    public final String dbPassword;
    public final String userAgent = "kr-us-stock-screener/1.0 (+local research tool; Java HttpClient)";
    public final int port;
    public final int krHistoryDays;
    public final int krOisTop;
    public final int usOisTop;
    public final int krFlowAggTop;
    public final int fmpDailyBudget;
    public final int limit;          // 0=무제한. 검증용 소량 실행 시 종목 수 상한
    public final boolean demo;

    private Config(Path home, boolean demo, int limit) {
        this.home = home;
        this.demo = demo;
        this.limit = limit;
        this.dbPath = home.resolve(demo ? "demo.db" : "screener.db");
        this.cacheDir = home.resolve("cache");
        this.logDir = home.resolve("logs");
        this.krxKey = env("KRX_API_KEY");
        this.dartKey = env("DART_API_KEY");
        this.tiingoKey = env("TIINGO_API_KEY");
        this.fmpKey = env("FMP_API_KEY");
        this.fredKey = env("FRED_API_KEY");
        this.secUserAgent = env("SEC_USER_AGENT");
        this.naverId = env("NAVER_CLIENT_ID");
        this.naverSecret = env("NAVER_CLIENT_SECRET");
        this.ecosKey = env("ECOS_API_KEY");
        this.kakaoRestKey = env("KAKAO_REST_API_KEY");
        this.kakaoClientSecret = env("KAKAO_CLIENT_SECRET");
        this.kakaoRedirectUri = env("KAKAO_REDIRECT_URI") != null ? env("KAKAO_REDIRECT_URI") : "http://localhost:" + intEnv("SCREENER_PORT", 8765) + "/kakao/callback";
        this.kakaoLinkUrl = env("KAKAO_LINK_URL") != null ? env("KAKAO_LINK_URL") : "http://localhost:" + intEnv("SCREENER_PORT", 8765);
        this.dbUrl = env("SCREENER_DB_URL");
        this.dbUser = env("SCREENER_DB_USER");
        this.dbPassword = env("SCREENER_DB_PASSWORD");
        this.port = intEnv("SCREENER_PORT", 8765);
        this.krHistoryDays = intEnv("KR_HISTORY_DAYS", 140);
        this.krOisTop = intEnv("KR_OIS_TOP", 300);
        this.usOisTop = intEnv("US_OIS_TOP", 500);
        this.krFlowAggTop = intEnv("KR_FLOW_AGG_TOP", 100);
        this.fmpDailyBudget = intEnv("FMP_DAILY_BUDGET", 150);
        for (String s : new String[]{krxKey, dartKey, tiingoKey, fmpKey, fredKey, secUserAgent, naverId, naverSecret, ecosKey, dbPassword, kakaoRestKey, kakaoClientSecret}) Log.registerSecret(s);
    }

    public static Config load(boolean demo, int limit) {
        String h = env("SCREENER_HOME");
        Path home = h != null ? Paths.get(h) : Paths.get("data");
        return new Config(home.toAbsolutePath(), demo, limit);
    }

    /** 테스트용: 임의 경로. */
    public static Config forTest(Path home) { return new Config(home, true, 0); }

    public static String env(String k) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static int intEnv(String k, int def) {
        String v = env(k);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    /** 키 존재 여부만 표시(값은 절대 출력하지 않음). */
    public String keyStatus() {
        return "KRX_API_KEY: " + set(krxKey) + ", DART_API_KEY: " + set(dartKey) + ", SEC_USER_AGENT: " + set(secUserAgent)
                + ", TIINGO_API_KEY: " + set(tiingoKey) + ", FMP_API_KEY: " + set(fmpKey) + ", NAVER_CLIENT_ID/SECRET: " + set(naverId == null ? null : naverSecret)
                + ", ECOS_API_KEY: " + set(ecosKey) + ", FRED_API_KEY(미사용): " + set(fredKey)
                + ", KAKAO_REST_API_KEY(선택): " + set(kakaoRestKey)
                + ", DB: " + (dbUrl == null ? "SQLite(" + dbPath.getFileName() + ")" : "MariaDB(SCREENER_DB_PASSWORD " + set(dbPassword) + ")");
    }

    private static String set(String v) { return v == null ? "미설정" : "설정됨"; }
}
