package screener.engine;

import screener.store.Db;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 모의매매 성과 분석용 CSV 내보내기(UTF-8 BOM). 모든 표는 run_id·mode·strategy_version 및 signal_id/order_id/fill id로 연결된다.
 * API 키·토큰은 어떤 표에도 없다.
 */
public final class PaperExport {
    private PaperExport() { }

    public static final Map<String, String> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("paper_signals", "SELECT id AS signal_id, run_id, mode, strategy_version, market, account, symbol, name, signal_date, side, action, stage, decision, reason,"
                + " msi, msi_band, msi_dynamic, msi_coverage, ois_s, ois_w, ois_coverage, ois_status, ois_confidence, prev_close, avg_vol20, avg_val20, rel_vol, atr14, atr_pct,"
                + " mkt_ret5, mkt_ret20, sector_ret20, planned_amount, planned_qty, liquidity_pre, params_json, obs_json, scores_json, created_at FROM paper_signals ORDER BY signal_date, id");
        TABLES.put("paper_orders", "SELECT id AS order_id, signal_id, run_id, mode, strategy_version, account, market, symbol, name, side, stage, action, planned_amount, planned_qty,"
                + " status, signal_date, target_date, status_reason, created_at, updated_at FROM paper_orders ORDER BY signal_date, id");
        TABLES.put("paper_fills", "SELECT id AS fill_id, order_id, signal_id, run_id, mode, strategy_version, account, market, symbol, name, side, stage, fill_date, prev_close, ref_open,"
                + " gap, fill_price, qty, gross, fee, tax, slippage_cost, net_cash, realized_pnl, day_volume, day_value, participation_day, participation_window, auction_volume, bid, ask,"
                + " spread, vol_1m, val_1m, vol_5m, val_5m, liquidity_assumption, mark_date, mark_price, net_ret_mark, obs_json, created_at FROM paper_fills ORDER BY fill_date, id");
        TABLES.put("paper_equity_daily", "SELECT e.account, a.mode, a.strategy_version, e.run_id, e.date, e.market, e.currency, e.cash, e.positions_value, e.equity, e.deposits_cum,"
                + " e.realized_cum, e.unrealized, e.n_fills, e.n_unfilled, e.n_deferred, e.benchmark, e.bench_close, e.fx_rate, e.fx_date, e.fx_source, e.equity_krw, e.valuation_note"
                + " FROM paper_equity_daily e JOIN paper_accounts a ON a.account=e.account ORDER BY e.date, e.account");
        TABLES.put("paper_cashflows", "SELECT c.id AS cashflow_id, c.account, a.mode, c.date, c.amount, c.type, c.note FROM paper_cashflows c JOIN paper_accounts a ON a.account=c.account ORDER BY c.date");
        TABLES.put("paper_positions", "SELECT account, symbol, name, qty, avg_cost, realized_pnl, stages, exits, first_fill, last_fill FROM paper_positions ORDER BY account, symbol");
        TABLES.put("paper_performance", "SELECT as_of, market, strategy_version, dimension, bucket, n, mean_net_ret, median_net_ret, win_rate, note FROM paper_performance ORDER BY as_of, market, dimension, bucket");
        TABLES.put("runs", "SELECT id AS run_id, market, mode, started_at, finished_at, as_of, status, error FROM runs ORDER BY id");
    }

    public static String csv(Db db, String table) throws Exception {
        if (table.equals("paper_equity_combined")) return toCsv(combined(db));
        String sql = TABLES.get(table);
        if (sql == null) throw new IllegalArgumentException("알 수 없는 표: " + table);
        List<Map<String, Object>> rows = db.query(sql);
        if (rows.isEmpty()) return "﻿" + String.join(",", db.columns(sql)) + "\r\n";
        return toCsv(rows);
    }

    /** 통합 평가: 날짜별 KRW 계좌 + USD 계좌×환율. 각 계좌는 해당일 또는 직전 평가값을 사용하고 사용 일자를 표시. */
    static List<Map<String, Object>> combined(Db db) throws Exception {
        List<Map<String, Object>> rows = db.query("SELECT account, date, equity, fx_rate, fx_date, fx_source, equity_krw FROM paper_equity_daily ORDER BY date");
        TreeMap<String, Map<String, Object>> byDate = new TreeMap<>();
        Map<String, Map<String, Object>> last = new LinkedHashMap<>();
        java.util.Set<String> dates = new java.util.TreeSet<>();
        for (Map<String, Object> r : rows) dates.add((String) r.get("date"));
        int i = 0;
        for (String d : dates) {
            while (i < rows.size() && ((String) rows.get(i).get("date")).compareTo(d) <= 0) { last.put((String) rows.get(i).get("account"), rows.get(i)); i++; }
            Map<String, Object> kr = last.get("KR-KRW"), us = last.get("US-USD");
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("date", d);
            o.put("kr_equity_krw", kr == null ? null : kr.get("equity"));
            o.put("kr_valuation_date", kr == null ? null : kr.get("date"));
            o.put("us_equity_usd", us == null ? null : us.get("equity"));
            o.put("us_valuation_date", us == null ? null : us.get("date"));
            o.put("fx_usdkrw", us == null ? null : us.get("fx_rate"));
            o.put("fx_date", us == null ? null : us.get("fx_date"));
            o.put("fx_source", us == null ? null : us.get("fx_source"));
            Double usk = us == null || us.get("equity_krw") == null ? null : ((Number) us.get("equity_krw")).doubleValue();
            Double krv = kr == null ? null : ((Number) kr.get("equity")).doubleValue();
            o.put("us_equity_krw", usk);
            o.put("total_krw", krv == null && usk == null ? null : (krv == null ? 0 : krv) + (usk == null ? 0 : usk));
            o.put("note", (kr == null || us == null) ? "한쪽 계좌 평가 없음 — 합계는 있는 계좌만" : us.get("equity_krw") == null ? "환율 결측 — 미국 계좌 원화 환산 제외" : "각 시장 현지 기준일 평가, 미국은 해당 환율 적용");
            byDate.put(d, o);
        }
        return new ArrayList<>(byDate.values());
    }

    public static List<Path> exportAll(Db db, Path dir) throws Exception {
        Files.createDirectories(dir);
        List<Path> out = new ArrayList<>();
        List<String> names = new ArrayList<>(TABLES.keySet());
        names.add("paper_equity_combined");
        for (String t : names) {
            Path f = dir.resolve(t + ".csv");
            Files.writeString(f, csv(db, t), StandardCharsets.UTF_8);
            out.add(f);
        }
        return out;
    }

    static String toCsv(List<Map<String, Object>> rows) {
        StringBuilder b = new StringBuilder("﻿");
        if (rows.isEmpty()) return b.append("date\r\n").toString();
        b.append(String.join(",", rows.get(0).keySet())).append("\r\n");
        for (Map<String, Object> r : rows) {
            boolean first = true;
            for (Object v : r.values()) {
                if (!first) b.append(',');
                first = false;
                b.append(cell(v));
            }
            b.append("\r\n");
        }
        return b.toString();
    }

    static String cell(Object v) {
        if (v == null) return "";
        String s;
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            s = Double.isNaN(d) || Double.isInfinite(d) ? "" : java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        } else s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
