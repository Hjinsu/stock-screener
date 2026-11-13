package screener;

import screener.report.KakaoReporter;
import screener.store.Db;
import screener.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 카카오 보고 — 모의 응답으로만 검증(실제 카카오 호출 없음). */
final class KakaoTests {
    private KakaoTests() { }

    static final class Mock implements KakaoReporter.Transport {
        final List<String> calls = new ArrayList<>();
        final List<Map<String, String>> forms = new ArrayList<>();
        int sendStatus = 200;
        boolean sendThrows = false;
        String refreshBody = "{\"access_token\":\"ACCESS_TOKEN_2\",\"expires_in\":21599}";

        @Override public KakaoReporter.Response post(String url, Map<String, String> headers, Map<String, String> form) throws IOException {
            calls.add(url);
            forms.add(form);
            if (url.endsWith("/oauth/token")) {
                if ("refresh_token".equals(form.get("grant_type"))) return new KakaoReporter.Response(200, refreshBody);
                return new KakaoReporter.Response(200, "{\"access_token\":\"ACCESS_TOKEN_1\",\"refresh_token\":\"REFRESH_TOKEN_1\",\"expires_in\":21599}");
            }
            if (url.endsWith("/memo/default/send")) {
                if (sendThrows) throw new IOException("Read timed out");
                return sendStatus == 200 ? new KakaoReporter.Response(200, "{\"result_code\":0}") : new KakaoReporter.Response(sendStatus, "{\"msg\":\"this access token does not exist\",\"code\":-401}");
            }
            return new KakaoReporter.Response(200, "{\"id\":1}");
        }

        long sends() { return calls.stream().filter(c -> c.endsWith("/memo/default/send")).count(); }
    }

    static void equity(Db db, String date, double eq) throws Exception {
        try (var ps = db.prepare("INSERT OR REPLACE INTO paper_equity_daily(account, date, market, currency, cash, positions_value, equity, deposits_cum, realized_cum, unrealized,"
                + " n_fills, n_unfilled, n_deferred) VALUES('KR-KRW',?,'KR','KRW',?,0,?,100000000,0,0,0,0,0)")) {
            ps.setString(1, date); ps.setDouble(2, eq); ps.setDouble(3, eq); ps.executeUpdate();
        }
    }

    static void all() throws Exception {
        Path h = Files.createTempDirectory("kakao");
        Config cfg = Config.forTest(h);
        cfg.kakaoRestKey = "TEST_REST_KEY_123";
        Mock m = new Mock();
        try (Db db = TestDb.open(h.resolve("k.db"))) {
            KakaoReporter k = new KakaoReporter(cfg, db, m);
            TestMain.eq(k.report("KR", "2026-11-12", true, "").get("status"), "DISABLED", "보고 꺼짐이 기본");
            db.setSetting("kakao_enabled_KR", "true");
            TestMain.eq(k.report("KR", "2026-11-12", true, "").get("status"), "NOT_CONNECTED", "미연결이면 발송 안 함");
            TestMain.check(k.authorizeUrl("st").contains("scope=talk_message") && k.authorizeUrl("st").contains("state=st"), "동의 항목·state 포함");
            k.exchangeCode("CODE");
            TestMain.check(k.connected(), "토큰 저장");
            String tok = Files.readString(h.resolve("secure").resolve("kakao-token.json"));
            TestMain.check(tok.contains("REFRESH_TOKEN_1"), "리프레시 토큰 저장");
            TestMain.check(db.count("SELECT COUNT(*) FROM settings WHERE value LIKE '%TOKEN%'") == 0, "토큰은 DB에 저장하지 않음");

            // 거래 없는 날: 체결 0건 보고
            equity(db, "2026-11-11", 100_000_000);
            equity(db, "2026-11-12", 100_250_000);
            TestMain.eq(k.report("KR", "2026-11-12", true, "").get("status"), "SENT", "일일 보고 발송");
            String text = (String) db.query("SELECT message_text FROM kakao_reports").get(0).get("message_text");
            TestMain.check(text.startsWith("[모의매매] KR 2026-11-12") && text.contains("체결 0건") && text.contains("총자산") && text.contains("누적"), "보고 내용: " + text);
            TestMain.check(text.length() <= KakaoReporter.TEXT_MAX, "200자 이내");
            for (String s : new String[]{"ACCESS_TOKEN_1", "REFRESH_TOKEN_1", "TEST_REST_KEY_123"}) TestMain.check(!text.contains(s), "메시지에 키·토큰 없음");
            TestMain.check(!Log.redact("Bearer ACCESS_TOKEN_1").contains("ACCESS_TOKEN_1"), "로그에서 토큰 마스킹");

            // 재실행: 중복 발송 없음
            long before = m.sends();
            TestMain.eq(k.report("KR", "2026-11-12", true, "").get("status"), "ALREADY_SENT", "재실행 시 재발송 안 함");
            TestMain.eq(m.sends(), before, "발송 호출 없음");

            // 응답 유실 → UNKNOWN, 자동 재발송 금지
            equity(db, "2026-11-13", 100_100_000);
            m.sendThrows = true;
            TestMain.eq(k.report("KR", "2026-11-13", true, "").get("status"), "UNKNOWN", "응답 유실은 불명확");
            m.sendThrows = false;
            before = m.sends();
            TestMain.eq(k.report("KR", "2026-11-13", true, "").get("status"), "ALREADY_UNKNOWN", "불명확은 자동 재발송 안 함");
            TestMain.eq(m.sends(), before, "발송 호출 없음(불명확)");

            // 명확한 실패(401) → FAILED → 다음 실행에서 재시도
            equity(db, "2026-11-16", 100_300_000);
            m.sendStatus = 401;
            TestMain.eq(k.report("KR", "2026-11-16", true, "").get("status"), "FAILED", "401 실패 기록");
            m.sendStatus = 200;
            TestMain.eq(k.report("KR", "2026-11-16", true, "").get("status"), "SENT", "실패 건은 재시도");
            TestMain.eq(((Number) db.query("SELECT attempts FROM kakao_reports WHERE trade_date='2026-11-16'").get(0).get("attempts")).intValue(), 2, "시도 횟수");

            // 미확정 → 별도 유형, 이후 확정 보고는 정상 발송
            TestMain.eq(k.report("KR", "2026-11-17", false, "기준일 계좌 평가 없음").get("type"), "DAILY_UNCONFIRMED", "미확정 구분");
            TestMain.check(((String) db.query("SELECT message_text FROM kakao_reports WHERE trade_date='2026-11-17'").get(0).get("message_text")).contains("미확정"), "미확정 표시");
            equity(db, "2026-11-17", 100_400_000);
            TestMain.eq(k.report("KR", "2026-11-17", true, "").get("type"), "DAILY", "확정 후 정상 보고");

            // 토큰 만료 → 갱신(응답에 refresh_token 없으면 기존 유지)
            Path tf = h.resolve("secure").resolve("kakao-token.json");
            Files.writeString(tf, Files.readString(tf).replaceAll("\"expires_at\":\\d+", "\"expires_at\":1"));
            equity(db, "2026-11-18", 100_500_000);
            TestMain.eq(k.report("KR", "2026-11-18", true, "").get("status"), "SENT", "만료 토큰 갱신 후 발송");
            TestMain.check(m.forms.stream().anyMatch(f -> "refresh_token".equals(f.get("grant_type")) && "REFRESH_TOKEN_1".equals(f.get("refresh_token"))), "리프레시 요청");
            String t2 = Files.readString(tf);
            TestMain.check(t2.contains("ACCESS_TOKEN_2") && t2.contains("REFRESH_TOKEN_1"), "새 액세스 토큰·기존 리프레시 유지");

            // 연결 해제
            k.disconnect();
            TestMain.check(!k.connected(), "해제 시 토큰 삭제");
        }
    }
}
