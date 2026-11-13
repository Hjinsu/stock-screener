package screener.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 버전별 스키마 마이그레이션. 테이블 정의는 한 번만 작성하고 토큰을 방언별 타입으로 치환한다.
 * %ID% 자동 증가 기본키, %K<n>% 키·인덱스용 문자열(MariaDB VARCHAR(n)), %T% 일반 문자열(MEDIUMTEXT), %R% 실수(DOUBLE, IEEE 754 — SQLite REAL과 비트 동일),
 * %I% 정수(BIGINT). 모든 마이그레이션은 재실행해도 안전하다(IF NOT EXISTS, 컬럼 존재 확인 후 추가).
 * 스키마를 바꿀 때는 기존 항목을 수정하지 말고 새 버전을 추가한다.
 */
public final class Schema {
    private Schema() { }

    public enum Dialect { SQLITE, MARIADB }

    public record Step(String sql, String addColumnTable, String addColumnName) { }

    public record Migration(int version, String description, List<Step> steps) { }

    static Step s(String sql) { return new Step(sql, null, null); }

    /** 컬럼이 없을 때만 추가. */
    static Step addColumn(String table, String column, String type) { return new Step("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type, table, column); }

    public static final List<Migration> MIGRATIONS = new ArrayList<>();
    static {
        MIGRATIONS.add(new Migration(1, "기본 스키마: 실행·점수·상태·전환·알림·MSI·추적·원천 캐시·설정", List.of(
            s("CREATE TABLE IF NOT EXISTS runs(id %ID%, market %K8%, mode %K16%, started_at %K32%, finished_at %K32%, as_of %K10%, completed_day_note %T%, status %K16%,"
                + " summary_json %T%, sources_json %T%, error %T%)"),
            s("CREATE TABLE IF NOT EXISTS scores(id %ID%, run_id %I%, model %K8%, market %K8%, symbol %K32%, name %T%, as_of %K10%, s %R%, w %R%, coverage %R%, range_high %R%,"
                + " status %K24%, confidence %K4%, rule_version %T%, card_json %T%, UNIQUE(model, market, symbol, as_of))"),
            s("CREATE INDEX IF NOT EXISTS ix_scores_q ON scores(market, model, as_of)"),
            s("CREATE TABLE IF NOT EXISTS candidate_state(model %K8%, market %K8%, symbol %K32%, name %T%, state %K24%, since %K10%, last_as_of %K10%, last_s %R%,"
                + " prev_state %K24%, prev_as_of %K10%, PRIMARY KEY(model, market, symbol))"),
            s("CREATE TABLE IF NOT EXISTS transitions(id %ID%, model %K8%, market %K8%, symbol %K32%, name %T%, as_of %K10%, from_state %K24%, to_state %K24%, s %R%,"
                + " reason %T%, UNIQUE(model, market, symbol, as_of))"),
            s("CREATE TABLE IF NOT EXISTS alerts(id %ID%, created_at %K32%, model %K8%, market %K8%, symbol %K32%, name %T%, as_of %K10%, type %K40%, title %T%, body_json %T%,"
                + " is_read %I% DEFAULT 0, UNIQUE(model, market, symbol, as_of, type))"),
            s("CREATE TABLE IF NOT EXISTS msi(market %K8%, as_of %K10%, score %R%, w %R%, provisional %I%, band %K24%, dynamic %K24%, ch1 %R%, ch5 %R%, ch20 %R%,"
                + " confidence %K4%, rule_version %T%, run_id %I%, detail_json %T%, PRIMARY KEY(market, as_of))"),
            s("CREATE TABLE IF NOT EXISTS tracking(id %ID%, model %K8%, market %K8%, symbol %K32%, name %T%, detect_date %K10%, detect_close %R%, detect_score %R%,"
                + " detect_state %K24%, bench %K16%, bench_close %R%, status %K16%, end_reason %T%, invalid_reason %T%, last_update %K32%,"
                + " UNIQUE(model, market, symbol, detect_date))"),
            s("CREATE TABLE IF NOT EXISTS checkpoints(tracking_id %I%, horizon %I%, date %K10%, close %R%, ret %R%, mfe %R%, mae %R%, bench_ret %R%, excess %R%,"
                + " sector_excess %R%, score_at %R%, state_at %K24%, hit10 %I%, hit15 %I%, PRIMARY KEY(tracking_id, horizon), FOREIGN KEY(tracking_id) REFERENCES tracking(id))"),
            s("CREATE TABLE IF NOT EXISTS krx_daily(date %K10%, code %K16%, market %K16%, name %T%, sect %T%, open %R%, high %R%, low %R%, close %R%, volume %R%, value %R%,"
                + " mcap %R%, shares %R%, PRIMARY KEY(date, code))"),
            s("CREATE INDEX IF NOT EXISTS ix_krx_code ON krx_daily(code, date)"),
            s("CREATE TABLE IF NOT EXISTS krx_dates(date %K10% PRIMARY KEY, status %K16%, `rows` %I%, checked_at %K32%)"),
            s("CREATE TABLE IF NOT EXISTS us_bars(symbol %K32%, date %K10%, open %R%, high %R%, low %R%, close %R%, volume %R%, src %K16%, PRIMARY KEY(symbol, date))"),
            s("CREATE TABLE IF NOT EXISTS flows(code %K16%, date %K10%, foreign_qty %R%, inst_qty %R%, foreign_hold %R%, close %R%, PRIMARY KEY(code, date))"),
            s("CREATE TABLE IF NOT EXISTS sectors(market %K8%, symbol %K32%, sector %K32%, sector_name %T%, source %T%, updated %K32%, PRIMARY KEY(market, symbol))"),
            s("CREATE TABLE IF NOT EXISTS settings(`key` %K100% PRIMARY KEY, value %T%)")
        )));
        MIGRATIONS.add(new Migration(2, "추적 종료 기준일 컬럼(tracking.end_as_of) — 재실행 결정성", List.of(
            addColumn("tracking", "end_as_of", "%K10%")
        )));
        MIGRATIONS.add(new Migration(3, "모의 자동매매(PAPER): 계좌·입출금·신호·주문·체결·보유·일별 평가·성과", List.of(
            s("CREATE TABLE IF NOT EXISTS paper_accounts(account %K16% PRIMARY KEY, market %K8%, currency %K8%, mode %K16%, start_cash %R%, cash %R%, created_as_of %K10%,"
                + " strategy_version %K40%)"),
            s("CREATE TABLE IF NOT EXISTS paper_cashflows(id %ID%, account %K16%, date %K10%, amount %R%, type %K24%, note %T%, UNIQUE(account, date, type))"),
            s("CREATE TABLE IF NOT EXISTS paper_signals(id %ID%, signal_key %K255% UNIQUE, run_id %I%, mode %K16%, strategy_version %K40%, params_json %T%, market %K8%,"
                + " account %K16%, symbol %K32%, name %T%, signal_date %K10%, side %K8%, action %K24%, stage %I%, decision %K16%, reason %T%, msi %R%, msi_band %K24%,"
                + " msi_dynamic %K24%, msi_coverage %R%, ois_s %R%, ois_w %R%, ois_coverage %R%, ois_status %K24%, ois_confidence %K4%, scores_json %T%, prev_close %R%,"
                + " avg_vol20 %R%, avg_val20 %R%, rel_vol %R%, atr14 %R%, atr_pct %R%, mkt_ret5 %R%, mkt_ret20 %R%, sector_ret20 %R%, planned_amount %R%, planned_qty %R%,"
                + " liquidity_pre %R%, obs_json %T%, created_at %K32%)"),
            s("CREATE TABLE IF NOT EXISTS paper_orders(id %ID%, signal_id %I% UNIQUE, run_id %I%, mode %K16%, strategy_version %K40%, account %K16%, market %K8%, symbol %K32%,"
                + " name %T%, side %K8%, stage %I%, action %K24%, planned_amount %R%, planned_qty %R%, status %K16%, signal_date %K10%, target_date %K10%, status_reason %T%,"
                + " created_at %K32%, updated_at %K32%, FOREIGN KEY(signal_id) REFERENCES paper_signals(id))"),
            s("CREATE TABLE IF NOT EXISTS paper_fills(id %ID%, order_id %I% UNIQUE, signal_id %I%, run_id %I%, mode %K16%, strategy_version %K40%, account %K16%, market %K8%,"
                + " symbol %K32%, name %T%, side %K8%, stage %I%, fill_date %K10%, prev_close %R%, ref_open %R%, gap %R%, fill_price %R%, qty %R%, gross %R%, fee %R%, tax %R%,"
                + " slippage_cost %R%, net_cash %R%, realized_pnl %R%, day_volume %R%, day_value %R%, participation_day %R%, participation_window %T%, auction_volume %R%,"
                + " bid %R%, ask %R%, spread %R%, vol_1m %R%, val_1m %R%, vol_5m %R%, val_5m %R%, liquidity_assumption %T%, mark_date %K10%, mark_price %R%, net_ret_mark %R%,"
                + " obs_json %T%, created_at %K32%, FOREIGN KEY(order_id) REFERENCES paper_orders(id))"),
            s("CREATE TABLE IF NOT EXISTS paper_positions(account %K16%, symbol %K32%, name %T%, qty %R%, avg_cost %R%, realized_pnl %R%, stages %T%, exits %T%,"
                + " first_fill %K10%, last_fill %K10%, PRIMARY KEY(account, symbol))"),
            s("CREATE TABLE IF NOT EXISTS paper_equity_daily(account %K16%, date %K10%, market %K8%, currency %K8%, cash %R%, positions_value %R%, equity %R%, deposits_cum %R%,"
                + " realized_cum %R%, unrealized %R%, n_fills %I%, n_unfilled %I%, n_deferred %I%, benchmark %K16%, bench_close %R%, fx_rate %R%, fx_date %K10%, fx_source %T%,"
                + " equity_krw %R%, valuation_note %T%, run_id %I%, PRIMARY KEY(account, date))"),
            s("CREATE TABLE IF NOT EXISTS paper_performance(as_of %K10%, market %K8%, strategy_version %K40%, dimension %K64%, bucket %K64%, n %I%, mean_net_ret %R%,"
                + " median_net_ret %R%, win_rate %R%, note %T%, PRIMARY KEY(as_of, market, strategy_version, dimension, bucket))")
        )));
        MIGRATIONS.add(new Migration(4, "뉴스 심리(NAVER API HUB): 일별 요약·기사", List.of(
            s("CREATE TABLE IF NOT EXISTS news_daily(market %K8%, date %K10%, score %R%, raw_score %R%, status %K16%, note %T%, n_raw %I%, n_kept %I%, n_dup %I%, n_ads %I%,"
                + " n_outside %I%, outlets %I%, levels_json %T%, intensity %R%, growth %R%, window_from %K40%, window_to %K40%, queries %T%, calls %I%, fetched_at %K32%,"
                + " rule_version %T%, PRIMARY KEY(market, date))"),
            s("CREATE TABLE IF NOT EXISTS news_items(market %K8%, date %K10%, outlet %K128%, title %T%, link %K500%, pub_ts %K40%, level %I%, PRIMARY KEY(market, date, link))")
        )));
    }

    static {
        MIGRATIONS.add(new Migration(5, "카카오톡 모의매매 보고 발송 기록(중복 방지·상태)", List.of(
            s("CREATE TABLE IF NOT EXISTS kakao_reports(id %ID%, account %K16%, market %K8%, trade_date %K10%, report_type %K24%, status %K16%, message_text %T%,"
                + " http_status %I%, error %T%, attempts %I%, created_at %K32%, sent_at %K32%, UNIQUE(account, market, trade_date, report_type))")
        )));
    }

    static {
        MIGRATIONS.add(new Migration(6, "일일·주간 보고서 저장, 상태 변화 알림 발송 기록, 신호 확인 시각", List.of(
            s("CREATE TABLE IF NOT EXISTS reports(id %ID%, market %K8%, report_type %K16%, period_key %K10%, checked_at %K32%, text %T%, content_json %T%,"
                + " created_at %K32%, UNIQUE(market, report_type, period_key))"),
            s("CREATE TABLE IF NOT EXISTS kakao_events(id %ID%, market %K8%, trade_date %K10%, event_type %K32%, subject %K64%, status %K16%, message_text %T%,"
                + " checked_at %K32%, http_status %I%, error %T%, attempts %I%, created_at %K32%, sent_at %K32%, UNIQUE(market, trade_date, event_type, subject))"),
            s("CREATE TABLE IF NOT EXISTS event_state(market %K8%, kind %K32%, subject %K64%, state %K16%, as_of %K10%, PRIMARY KEY(market, kind, subject))"),
            addColumn("kakao_reports", "checked_at", "%K32%")
        )));
    }

    /** 이관 대상 테이블(참조 순서)과 정렬 키. 체크섬은 이 정렬 키 순서로 계산한다. */
    public static final Map<String, String> TABLE_ORDER = new LinkedHashMap<>();
    static {
        TABLE_ORDER.put("runs", "id");
        TABLE_ORDER.put("scores", "id");
        TABLE_ORDER.put("candidate_state", "model, market, symbol");
        TABLE_ORDER.put("transitions", "id");
        TABLE_ORDER.put("alerts", "id");
        TABLE_ORDER.put("msi", "market, as_of");
        TABLE_ORDER.put("tracking", "id");
        TABLE_ORDER.put("checkpoints", "tracking_id, horizon");
        TABLE_ORDER.put("krx_dates", "date");
        TABLE_ORDER.put("krx_daily", "date, code");
        TABLE_ORDER.put("us_bars", "symbol, date");
        TABLE_ORDER.put("flows", "code, date");
        TABLE_ORDER.put("sectors", "market, symbol");
        TABLE_ORDER.put("settings", "`key`");
        TABLE_ORDER.put("paper_accounts", "account");
        TABLE_ORDER.put("paper_cashflows", "id");
        TABLE_ORDER.put("paper_signals", "id");
        TABLE_ORDER.put("paper_orders", "id");
        TABLE_ORDER.put("paper_fills", "id");
        TABLE_ORDER.put("paper_positions", "account, symbol");
        TABLE_ORDER.put("paper_equity_daily", "account, date");
        TABLE_ORDER.put("paper_performance", "as_of, market, strategy_version, dimension, bucket");
        TABLE_ORDER.put("news_daily", "market, date");
        TABLE_ORDER.put("news_items", "market, date, link");
        TABLE_ORDER.put("kakao_reports", "id");
        TABLE_ORDER.put("reports", "id");
        TABLE_ORDER.put("kakao_events", "id");
        TABLE_ORDER.put("event_state", "market, kind, subject");
    }

    private static final Pattern K = Pattern.compile("%K(\\d+)%");

    public static String ddl(String tpl, Dialect d) {
        boolean m = d == Dialect.MARIADB;
        String s = tpl.replace("%ID%", m ? "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT")
                .replace("%T%", m ? "MEDIUMTEXT" : "TEXT")
                .replace("%R%", m ? "DOUBLE" : "REAL")
                .replace("%I%", m ? "BIGINT" : "INTEGER");
        Matcher mt = K.matcher(s);
        StringBuilder b = new StringBuilder();
        while (mt.find()) mt.appendReplacement(b, m ? "VARCHAR(" + mt.group(1) + ")" : "TEXT");
        mt.appendTail(b);
        s = b.toString();
        if (m && s.startsWith("CREATE TABLE")) s += " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
        return s;
    }

    /** SQLite 문법으로 작성된 DML을 방언에 맞게 변환(INSERT OR IGNORE/REPLACE, ON CONFLICT ... excluded.x). */
    public static String dml(String sql, Dialect d) {
        if (d == Dialect.SQLITE) return sql;
        String s = sql.replace("INSERT OR IGNORE INTO", "INSERT IGNORE INTO").replace("INSERT OR REPLACE INTO", "REPLACE INTO");
        s = s.replaceAll("ON CONFLICT\\s*\\([^)]*\\)\\s*DO UPDATE SET", "ON DUPLICATE KEY UPDATE");
        s = s.replaceAll("excluded\\.(\\w+)", "VALUES($1)");
        return s;
    }

    public static int latestVersion() { return MIGRATIONS.get(MIGRATIONS.size() - 1).version(); }
}
