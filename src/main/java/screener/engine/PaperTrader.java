package screener.engine;

import screener.model.Bar;
import screener.model.Security;
import screener.provider.MarketDataProvider;
import screener.store.Db;
import screener.util.Json;
import screener.util.Log;
import screener.util.Num;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 모의 자동매매(원본 02 역발상 전략). 실거래 연결 없음.
 * - 신호: 확정장 종가 기준으로 진입(20/30/30/20 분할)·청산(+10/+15%, MSI 80/90/고점 하락 전환, 가설 훼손)을 판단.
 * - 체결: 해당 시장 다음 거래일 시가에 슬리피지·수수료·세금을 반영해 체결. 시가 미확인·거래정지면 체결 없이 사유만 기록.
 * - 시가 이후 정보(당일 거래량·종가 등)는 체결 판정에 쓰지 않고 사후 분석 필드로만 저장한다.
 * - 신호·주문·체결은 자연키(UNIQUE)로 한 번만 기록되며, 체결과 현금·보유 갱신은 한 트랜잭션이다.
 */
public final class PaperTrader {
    public static final String VERSION = "PAPER v1.0";
    public static final String VERSION_DESC = "PAPER v1.0 — 원본02 분할 진입 20/30/30/20(단계 조건=OIS 시나리오), 청산 +10%(1/3)·+15%(1/2)·MSI≥80(1/3)·MSI≥90(전량)·MSI 고점 하락 전환(전량)·가설 훼손(전량), 다음 거래일 시가 체결";
    static final double[] STAGE_W = {0.20, 0.30, 0.30, 0.20};
    public static final String LIQ_ASSUMPTION = "일봉 시가 체결: 유동성 확인 없는 모의 체결 가정(시가에 전량 체결 가정)";

    private final Db db;
    private final MarketDataProvider p;
    private final MarketContext ctx;
    private final long runId;
    private final String mode, account, currency, market;
    private final Params prm;
    private final String now = LocalDateTime.now().withNano(0).toString();
    private final List<LocalDate> cal;

    public static final class Params {
        public double startCash, planPct, maxSymbolPct, reservePct, feeBuy, feeSell, taxSell, slipBps;

        public static Params load(Db db, String market) throws SQLException {
            Params x = new Params();
            boolean kr = "KR".equals(market);
            x.startCash = d(db, "paper_start_" + (kr ? "KRW" : "USD"), kr ? 100_000_000 : 100_000);
            x.planPct = d(db, "paper_plan_pct", 0.10);
            x.maxSymbolPct = d(db, "paper_max_symbol_pct", 0.15);
            x.reservePct = d(db, "paper_cash_reserve_pct", 0.20);
            x.feeBuy = d(db, "paper_fee_buy_" + market, kr ? 0.00015 : 0.0);
            x.feeSell = d(db, "paper_fee_sell_" + market, kr ? 0.00015 : 0.0);
            x.taxSell = d(db, "paper_tax_sell_" + market, kr ? 0.0018 : 0.0);
            x.slipBps = d(db, "paper_slip_bps_" + market, kr ? 10 : 5);
            return x;
        }

        static double d(Db db, String k, double def) throws SQLException { return Double.parseDouble(db.setting(k, String.valueOf(def))); }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startCash", startCash); m.put("planPct", planPct); m.put("maxSymbolPct", maxSymbolPct); m.put("reservePct", reservePct);
            m.put("feeBuy", feeBuy); m.put("feeSell", feeSell); m.put("taxSell", taxSell); m.put("slipBps", slipBps);
            return m;
        }
    }

    public PaperTrader(Db db, MarketDataProvider p, MarketContext ctx, long runId) throws SQLException {
        this.db = db;
        this.p = p;
        this.ctx = ctx;
        this.runId = runId;
        this.market = ctx.market;
        this.mode = p.isDemo() ? "demo" : "paper";
        this.currency = "KR".equals(market) ? "KRW" : "USD";
        this.account = market + "-" + currency;
        this.prm = Params.load(db, market);
        List<Bar> ix = ctx.index.getOrDefault("KR".equals(market) ? "KOSPI" : "SPX", List.of());
        this.cal = new ArrayList<>();
        for (Bar b : ix) cal.add(b.date());
    }

    public static Set<String> heldOrPending(Db db, String market) throws SQLException {
        Set<String> s = new LinkedHashSet<>();
        for (Map<String, Object> r : db.query("SELECT symbol FROM paper_positions WHERE account LIKE ? AND qty>0", market + "-%")) s.add((String) r.get("symbol"));
        for (Map<String, Object> r : db.query("SELECT symbol FROM paper_orders WHERE market=? AND status='PENDING'", market)) s.add((String) r.get("symbol"));
        return s;
    }

    private String openTime() { return "KR".equals(market) ? "09:00 KST 시가" : "09:30 ET 시가"; }
    private String closeTime() { return "KR".equals(market) ? "15:30 KST 종가" : "16:00 ET 종가"; }

    public Map<String, Object> run(Map<String, ScoreCard> ois, Map<String, Object> msi) throws Exception {
        Map<String, Object> sum = new LinkedHashMap<>();
        ensureAccount();
        int[] f = processPending();
        sum.put("filled", f[0]);
        sum.put("unfilled", f[1]);
        sum.put("waiting", f[2]);
        int[] ex = exits(ois, msi);
        int[] en = entries(ois, msi);
        sum.put("exitOrders", ex[0]);
        sum.put("entryOrders", en[0]);
        sum.put("deferred", en[1] + ex[1]);
        sum.put("equityDays", equity());
        performance();
        Map<String, Object> a = db.query("SELECT cash FROM paper_accounts WHERE account=?", account).get(0);
        sum.put("cash", a.get("cash"));
        sum.put("account", account);
        sum.put("strategyVersion", VERSION);
        return sum;
    }

    private void ensureAccount() throws SQLException {
        if (db.count("SELECT COUNT(*) FROM paper_accounts WHERE account=?", account) > 0) return;
        synchronized (db) {
            Connection c = db.conn();
            c.setAutoCommit(false);
            try (PreparedStatement a = db.prepare("INSERT OR IGNORE INTO paper_accounts(account, market, currency, mode, start_cash, cash, created_as_of, strategy_version) VALUES(?,?,?,?,?,?,?,?)");
                 PreparedStatement b = db.prepare("INSERT OR IGNORE INTO paper_cashflows(account, date, amount, type, note) VALUES(?,?,?,'INITIAL','가상 시작금')")) {
                a.setString(1, account); a.setString(2, market); a.setString(3, currency); a.setString(4, mode); a.setDouble(5, prm.startCash);
                a.setDouble(6, prm.startCash); a.setString(7, ctx.asOf.toString()); a.setString(8, VERSION);
                a.executeUpdate();
                b.setString(1, account); b.setString(2, ctx.asOf.toString()); b.setDouble(3, prm.startCash);
                b.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    LocalDate nextTradingDay(LocalDate d) {
        for (LocalDate x : cal) if (x.isAfter(d)) return x;
        return null;
    }

    // ---------------- 체결 ----------------
    /** 반환 {체결, 미체결, 대기} */
    private int[] processPending() throws Exception {
        int filled = 0, unfilled = 0, waiting = 0;
        for (Map<String, Object> o : db.query("SELECT * FROM paper_orders WHERE account=? AND status='PENDING' ORDER BY id", account)) {
            long oid = ((Number) o.get("id")).longValue();
            LocalDate sd = LocalDate.parse((String) o.get("signal_date"));
            LocalDate t = nextTradingDay(sd);
            if (t == null || t.isAfter(ctx.asOf)) { waiting++; continue; }
            String sym = (String) o.get("symbol");
            Security s = ctx.secs.get(sym);
            if (s == null) { s = new Security(market, sym, (String) o.get("name")); }
            List<Bar> b = ctx.bars.getOrDefault(sym, List.of());
            MarketDataProvider.OpenObs ob = p.openOn(s, t, b);
            if (ob.open() == null) {
                setOrder(oid, "UNFILLED", t, "시가 미확인 — 체결 기록 없음: " + ob.note() + " (" + ob.source() + ")");
                unfilled++;
                continue;
            }
            Double prevClose = null;
            for (Bar x : b) if (x.date().isBefore(t)) prevClose = x.close();
            String r = fill(o, t, ob, prevClose);
            if (r == null) filled++; else { setOrder(oid, "UNFILLED", t, r); unfilled++; }
        }
        return new int[]{filled, unfilled, waiting};
    }

    private void setOrder(long id, String st, LocalDate t, String reason) throws SQLException {
        try (PreparedStatement ps = db.prepare("UPDATE paper_orders SET status=?, target_date=?, status_reason=?, updated_at=? WHERE id=? AND status='PENDING'")) {
            ps.setString(1, st); ps.setString(2, t.toString()); ps.setString(3, reason); ps.setString(4, now); ps.setLong(5, id);
            ps.executeUpdate();
        }
    }

    /** 체결·현금·보유를 한 트랜잭션으로 갱신. 실패 사유가 있으면 반환(체결 기록 없음). */
    private String fill(Map<String, Object> o, LocalDate t, MarketDataProvider.OpenObs ob, Double prevClose) throws SQLException {
        String side = (String) o.get("side");
        String sym = (String) o.get("symbol");
        double open = ob.open();
        double slip = prm.slipBps / 10000.0;
        synchronized (db) {
            Connection c = db.conn();
            c.setAutoCommit(false);
            try {
                double cash = ((Number) db.query("SELECT cash FROM paper_accounts WHERE account=?", account).get(0).get("cash")).doubleValue();
                List<Map<String, Object>> pos = db.query("SELECT * FROM paper_positions WHERE account=? AND symbol=?", account, sym);
                double hq = pos.isEmpty() ? 0 : ((Number) pos.get(0).get("qty")).doubleValue();
                double avg = pos.isEmpty() ? 0 : ((Number) pos.get(0).get("avg_cost")).doubleValue();
                double price, qty, gross, fee, tax, net, realized = 0;
                if ("BUY".equals(side)) {
                    price = open * (1 + slip);
                    double amt = ((Number) o.get("planned_amount")).doubleValue();
                    double room = prm.startCash * prm.maxSymbolPct - hq * avg;
                    double avail = cash - prm.startCash * prm.reservePct;
                    double use = Math.min(amt, Math.min(room, avail));
                    qty = Math.floor(use / (price * (1 + prm.feeBuy)));
                    if (qty <= 0) {
                        c.rollback();
                        return "체결 수량 0 — 계획 " + Num.money(market, amt) + ", 종목 한도 잔여 " + Num.money(market, room) + ", 가용 현금(유보 " + Math.round(prm.reservePct * 100) + "% 제외) " + Num.money(market, avail);
                    }
                    gross = price * qty;
                    fee = gross * prm.feeBuy;
                    tax = 0;
                    net = -(gross + fee);
                } else {
                    price = open * (1 - slip);
                    qty = Math.min(((Number) o.get("planned_qty")).doubleValue(), hq);
                    if (qty <= 0) { c.rollback(); return "보유 수량 없음"; }
                    gross = price * qty;
                    fee = gross * prm.feeSell;
                    tax = gross * prm.taxSell;
                    net = gross - fee - tax;
                    realized = net - avg * qty;
                }
                double slipCost = Math.abs(price - open) * qty;
                Double gap = prevClose == null || prevClose <= 0 ? null : open / prevClose - 1;
                Double part = ob.dayVolume() == null || ob.dayVolume() <= 0 ? null : qty / ob.dayVolume();
                String avail = now;
                Map<String, Object> obs = new LinkedHashMap<>();
                obs.put("ref_open", obsMeta(t + " " + openTime(), avail, ob.source(), "체결일 시가(단일 관측)", null));
                obs.put("prev_close", obsMeta(prevDate(t) + " " + closeTime(), avail, p.priceSourceLabel(), "체결 직전 거래일 종가", null));
                obs.put("day_volume", obsMeta(t + " 장 마감 집계", avail, ob.source(), "체결일 전체(일봉)", "사후 분석용 — 시가 주문 결정·체결 판정에 사용하지 않음"));
                obs.put("auction_volume", obsMeta(null, null, null, "개장 동시호가", "결측: 일봉 공급원은 동시호가 체결량을 제공하지 않음"));
                obs.put("bid_ask_spread", obsMeta(null, null, null, "모의 체결 직전 최우선 호가", "결측: 무료 공급원에서 호가 미제공"));
                obs.put("vol_1m_5m", obsMeta(null, null, null, "개장 후 첫 1분·5분", "결측: 분봉 미수집"));
                try (PreparedStatement ps = db.prepare("INSERT INTO paper_fills(order_id, signal_id, run_id, mode, strategy_version, account, market, symbol, name, side, stage, fill_date," +
                        " prev_close, ref_open, gap, fill_price, qty, gross, fee, tax, slippage_cost, net_cash, realized_pnl, day_volume, day_value, participation_day, participation_window," +
                        " auction_volume, bid, ask, spread, vol_1m, val_1m, vol_5m, val_5m, liquidity_assumption, obs_json, created_at)" +
                        " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,?,?,?)")) {
                    int i = 1;
                    ps.setLong(i++, ((Number) o.get("id")).longValue());
                    ps.setLong(i++, ((Number) o.get("signal_id")).longValue());
                    ps.setLong(i++, runId);
                    ps.setString(i++, (String) o.get("mode"));
                    ps.setString(i++, (String) o.get("strategy_version"));
                    ps.setString(i++, account); ps.setString(i++, market); ps.setString(i++, sym); ps.setString(i++, (String) o.get("name"));
                    ps.setString(i++, side); ps.setObject(i++, o.get("stage")); ps.setString(i++, t.toString());
                    setD(ps, i++, prevClose); ps.setDouble(i++, open); setD(ps, i++, gap); ps.setDouble(i++, price); ps.setDouble(i++, qty);
                    ps.setDouble(i++, gross); ps.setDouble(i++, fee); ps.setDouble(i++, tax); ps.setDouble(i++, slipCost); ps.setDouble(i++, net);
                    ps.setDouble(i++, realized); setD(ps, i++, ob.dayVolume()); setD(ps, i++, ob.dayValue()); setD(ps, i++, part);
                    ps.setString(i++, part == null ? "체결일 전체 거래량 없음" : "분모=체결일 전체 거래량(일봉, " + t + ") — 사후 분석용");
                    ps.setString(i++, LIQ_ASSUMPTION);
                    ps.setString(i++, Json.write(obs));
                    ps.setString(i++, now);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = db.prepare("UPDATE paper_orders SET status='FILLED', target_date=?, status_reason=?, updated_at=? WHERE id=?")) {
                    ps.setString(1, t.toString()); ps.setString(2, "다음 거래일 시가 체결(" + LIQ_ASSUMPTION + ")"); ps.setString(3, now); ps.setLong(4, ((Number) o.get("id")).longValue());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = db.prepare("UPDATE paper_accounts SET cash=cash+? WHERE account=?")) {
                    ps.setDouble(1, net); ps.setString(2, account); ps.executeUpdate();
                }
                if ("BUY".equals(side)) {
                    double nq = hq + qty;
                    double navg = (hq * avg + gross + fee) / nq;
                    String stages = pos.isEmpty() ? "" : (String) pos.get(0).get("stages");
                    stages = (stages == null || stages.isEmpty() ? "" : stages + ",") + o.get("stage");
                    try (PreparedStatement ps = db.prepare("INSERT INTO paper_positions(account, symbol, name, qty, avg_cost, realized_pnl, stages, exits, first_fill, last_fill) VALUES(?,?,?,?,?,0,?,'',?,?)" +
                            " ON CONFLICT(account, symbol) DO UPDATE SET exits=CASE WHEN paper_positions.qty<=0 THEN '' ELSE paper_positions.exits END," +
                            " qty=excluded.qty, avg_cost=excluded.avg_cost, stages=excluded.stages, last_fill=excluded.last_fill")) {
                        ps.setString(1, account); ps.setString(2, sym); ps.setString(3, (String) o.get("name")); ps.setDouble(4, nq); ps.setDouble(5, navg);
                        ps.setString(6, stages); ps.setString(7, t.toString()); ps.setString(8, t.toString());
                        ps.executeUpdate();
                    }
                } else {
                    double nq = hq - qty;
                    String exits = (String) pos.get(0).get("exits");
                    exits = (exits == null || exits.isEmpty() ? "" : exits + ",") + o.get("action");
                    try (PreparedStatement ps = db.prepare("UPDATE paper_positions SET qty=?, realized_pnl=realized_pnl+?, exits=?, last_fill=?, stages=CASE WHEN ?<=0 THEN '' ELSE stages END" +
                            ", avg_cost=CASE WHEN ?<=0 THEN 0 ELSE avg_cost END WHERE account=? AND symbol=?")) {
                        ps.setDouble(1, nq); ps.setDouble(2, realized); ps.setString(3, nq <= 0 ? "" : exits); ps.setString(4, t.toString());
                        ps.setDouble(5, nq); ps.setDouble(6, nq); ps.setString(7, account); ps.setString(8, sym);
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return null;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private String prevDate(LocalDate t) {
        LocalDate prev = null;
        for (LocalDate x : cal) if (x.isBefore(t)) prev = x;
        return prev == null ? "?" : prev.toString();
    }

    static Map<String, Object> obsMeta(String dataTime, String availableAt, String source, String window, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataTime", dataTime);
        m.put("availableAt", availableAt);
        m.put("source", source);
        m.put("window", window);
        if (note != null) m.put("note", note);
        return m;
    }

    private static void setD(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null || v.isNaN()) ps.setNull(i, java.sql.Types.REAL); else ps.setDouble(i, v);
    }

    // ---------------- 신호 ----------------
    private Map<String, Object> position(String sym) throws SQLException {
        List<Map<String, Object>> r = db.query("SELECT * FROM paper_positions WHERE account=? AND symbol=?", account, sym);
        return r.isEmpty() ? null : r.get(0);
    }

    private boolean hasPending(String sym) throws SQLException {
        return db.count("SELECT COUNT(*) FROM paper_orders WHERE account=? AND symbol=? AND status='PENDING'", account, sym) > 0;
    }

    private static Double msiScore(Map<String, Object> msi) {
        return msi == null || msi.get("score") == null ? null : ((Number) msi.get("score")).doubleValue();
    }

    /** 반환 {주문 수, 보류 수} */
    private int[] entries(Map<String, ScoreCard> ois, Map<String, Object> msi) throws Exception {
        int orders = 0, deferred = 0;
        for (ScoreCard c : ois.values()) {
            boolean cand = "INTEREST".equals(c.status) || "PRIORITY".equals(c.status);
            boolean pendingHigh = "PENDING".equals(c.status) && c.rangeHigh() >= 65;
            if (!cand && !pendingHigh) continue;
            if (hasPending(c.symbol)) continue;
            Map<String, Object> pos = position(c.symbol);
            int done = 0;
            if (pos != null && ((Number) pos.get("qty")).doubleValue() > 0) {
                for (String x : String.valueOf(pos.get("stages")).split(",")) if (!x.isBlank()) done = Math.max(done, Integer.parseInt(x.trim()));
            }
            int k = done + 1;
            if (k > 4) continue;
            if (pos != null && pos.get("exits") != null && !String.valueOf(pos.get("exits")).isEmpty()) continue; // 청산 진행 중이면 추가 매수 안 함
            List<String> why = new ArrayList<>();
            if (pendingHigh) why.add("OIS 확인 대기: " + c.statusReason);
            if (msiScore(msi) == null) why.add("MSI 미산출 — " + (msi == null ? "" : String.valueOf(msi.get("reason"))));
            if (c.coverage() < 70) why.add("OIS 커버리지 " + c.coverage() + "% < 70%");
            if (!c.mandatoryPassed) why.add("필수 재무·공시 검증 미완료");
            List<Bar> b = ctx.bars.getOrDefault(c.symbol, List.of());
            if (b.isEmpty() || !b.get(b.size() - 1).date().equals(ctx.asOf)) why.add("신호일 종가 미확보(자료 지연)");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sc = (List<Map<String, Object>>) c.info.get("scenario");
            Boolean met = sc == null || sc.size() < k ? null : (Boolean) sc.get(k - 1).get("met");
            if (Boolean.FALSE.equals(met)) continue; // 단계 조건이 이미 미충족으로 판정되면 주문 대상이 아니므로 보류도 기록하지 않음
            if (met == null) why.add(k + "차 진입 조건 판단 불가(" + (sc == null ? "시나리오 없음" : sc.get(k - 1).get("observed")) + ")");
            String action = "BUY_STAGE" + k;
            double planned = prm.startCash * prm.planPct * STAGE_W[k - 1];
            if (!why.isEmpty()) {
                if (signal(c, msi, "BUY", action, k, "DEFER", "필수 데이터 부족으로 주문 보류: " + String.join("; ", why), planned, null) != null) deferred++;
                continue;
            }
            if (!met) continue; // 조건 미충족: 신호 없음
            String reason = k + "차(" + Math.round(STAGE_W[k - 1] * 100) + "%) 조건 충족: " + sc.get(k - 1).get("condition") + " — " + sc.get(k - 1).get("observed")
                    + "; OIS " + c.S() + "/" + c.W() + " " + c.statusLabel + "; " + c.info.get("declineType");
            Long sid = signal(c, msi, "BUY", action, k, "ORDER", reason, planned, null);
            if (sid != null && order(sid, c, "BUY", action, k, planned, null)) orders++;
        }
        return new int[]{orders, deferred};
    }

    /** 반환 {주문 수, 보류 수} */
    private int[] exits(Map<String, ScoreCard> ois, Map<String, Object> msi) throws Exception {
        int orders = 0, deferred = 0;
        Double m = msiScore(msi);
        String dyn = msi == null ? null : (String) msi.get("dynamic");
        for (Map<String, Object> pos : db.query("SELECT * FROM paper_positions WHERE account=? AND qty>0", account)) {
            String sym = (String) pos.get("symbol");
            if (hasPending(sym)) continue;
            double qty = ((Number) pos.get("qty")).doubleValue(), avg = ((Number) pos.get("avg_cost")).doubleValue();
            Set<String> done = new LinkedHashSet<>(List.of(String.valueOf(pos.get("exits")).split(",")));
            List<Bar> b = ctx.bars.getOrDefault(sym, List.of());
            ScoreCard c = ois.get(sym);
            if (b.isEmpty() || !b.get(b.size() - 1).date().equals(ctx.asOf)) {
                ScoreCard stub = c != null ? c : stubCard(sym, (String) pos.get("name"));
                if (signal(stub, msi, "SELL", "EXIT_CHECK", null, "DEFER", "보유 종목 신호일 종가 미확보 — 청산 판단 보류", null, null) != null) deferred++;
                continue;
            }
            double close = b.get(b.size() - 1).close();
            double ret = avg > 0 ? close / avg - 1 : 0;
            String action = null, reason = null;
            double frac = 0;
            boolean thesisBroken = c != null && (c.blocked || "EXCLUDED_RISK".equals(c.status)
                    || c.items.stream().anyMatch(i -> i.key.equals("trend") && i.grade != null && i.grade == 0.0));
            if (thesisBroken) { action = "EXIT_THESIS"; frac = 1; reason = "투자 가설 훼손: " + (c.blocked ? c.blockReason : "실적 추세 악화 또는 위험 공시") + " — 수익률과 무관하게 전량"; }
            else if (m != null && m >= 90 && !done.contains("EXIT_MSI90")) { action = "EXIT_MSI90"; frac = 1; reason = "MSI " + m + " ≥ 90(극단적 환희) — 적극적 차익실현(전량)"; }
            else if (m != null && done.contains("EXIT_MSI80") && dyn != null && (dyn.equals("탐욕 둔화") || dyn.equals("환희 정점 후보"))) { action = "EXIT_MSI_TURN"; frac = 1; reason = "MSI 고점 하락 전환(" + dyn + ") — 잔여 물량 매도"; }
            else if (m != null && m >= 80 && !done.contains("EXIT_MSI80")) { action = "EXIT_MSI80"; frac = 1.0 / 3; reason = "MSI " + m + " ≥ 80 — 보유 비중 축소(1/3)"; }
            else if (ret >= 0.15 && !done.contains("EXIT_TP2")) { action = "EXIT_TP2"; frac = 0.5; reason = "보유 수익률 " + Num.pct(ret) + " ≥ +15% — 추가 차익실현(잔여의 1/2)"; }
            else if (ret >= 0.10 && !done.contains("EXIT_TP1")) { action = "EXIT_TP1"; frac = 1.0 / 3; reason = "보유 수익률 " + Num.pct(ret) + " ≥ +10% — 1차 차익실현(1/3)"; }
            if (action == null) continue;
            if (c == null) reason += " (OIS 미산출 — 가설 점검 불가)";
            if (m == null) reason += " (MSI 미산출 — 심리 기반 청산 미평가)";
            double q = frac >= 1 ? qty : Math.max(1, Math.floor(qty * frac));
            ScoreCard sc = c != null ? c : stubCard(sym, (String) pos.get("name"));
            Long sid = signal(sc, msi, "SELL", action, null, "ORDER", reason + "; 평균단가 " + Num.f2(avg) + ", 종가 " + Num.f2(close), null, q);
            if (sid != null && order(sid, sc, "SELL", action, null, null, q)) orders++;
        }
        return new int[]{orders, deferred};
    }

    private ScoreCard stubCard(String sym, String name) {
        ScoreCard s = new ScoreCard("OIS", "-", market, sym);
        s.name = name;
        s.status = "UNSCORED";
        return s;
    }

    /** 신호 저장(신호 시점 데이터 스냅샷). 같은 신호키가 이미 있으면 null. */
    private Long signal(ScoreCard c, Map<String, Object> msi, String side, String action, Integer stage, String decision, String reason, Double planned, Double plannedQty) throws Exception {
        String key = String.join("|", mode, VERSION, market, c.symbol, ctx.asOf.toString(), action);
        if (db.count("SELECT COUNT(*) FROM paper_signals WHERE signal_key=?", key) > 0) return null;
        List<Bar> b = ctx.bars.getOrDefault(c.symbol, List.of());
        Security s = ctx.secs.get(c.symbol);
        List<Bar> bm = s == null ? List.of() : ctx.bench(s);
        Double prevClose = b.isEmpty() ? null : b.get(b.size() - 1).close();
        Double avgVol = null, avgVal = Indicators.avgValue(b, 20), relVol = b.size() > 20 ? Indicators.volMultipleAt(b, b.size() - 1, 20) : null;
        if (b.size() >= 20) { double v = 0; for (int i = b.size() - 20; i < b.size(); i++) v += b.get(i).volume(); avgVol = v / 20; }
        List<Double> atr = Indicators.atrPct(b, 14);
        Double atrPct = atr.isEmpty() ? null : atr.get(atr.size() - 1);
        Double atrAbs = atrPct == null || prevClose == null ? null : atrPct * prevClose;
        List<Double> p20 = new ArrayList<>();
        if (s != null) for (MarketContext.PeerStat ps : ctx.peersOf(s)) if (ps.r20() != null) p20.add(ps.r20());
        Double sect = p20.size() >= 4 ? Num.median(p20) : null;
        if (plannedQty == null && planned != null && prevClose != null && prevClose > 0) plannedQty = Math.floor(planned / prevClose);
        Double liq = plannedQty == null || avgVol == null || avgVol <= 0 ? null : plannedQty / avgVol;
        String avail = now;
        String close = ctx.asOf + " " + closeTime();
        String src = p.priceSourceLabel();
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("prev_close", obsMeta(close, avail, src, "신호일 종가(체결일 전일 종가)", null));
        obs.put("avg_vol20", obsMeta(close, avail, src, "신호일 포함 최근 20거래일", null));
        obs.put("avg_val20", obsMeta(close, avail, src, "신호일 포함 최근 20거래일", b.isEmpty() || !b.get(b.size() - 1).valueApprox() ? null : "거래대금=종가×거래량 근사"));
        obs.put("rel_vol", obsMeta(close, avail, src, "신호일 거래량 / 직전 20거래일 평균(신호일 제외)", null));
        obs.put("atr14", obsMeta(close, avail, src, "14거래일 단순 평균 TR", null));
        obs.put("mkt_ret", obsMeta(close, avail, src + " / 벤치마크 " + (s == null ? "-" : p.benchmarkKey(s)), "5·20거래일", null));
        obs.put("sector_ret20", obsMeta(close, avail, "유니버스 내 동일 업종 중앙값", "20거래일", sect == null ? "업종 비교 종목 <4 — 결측" : null));
        obs.put("msi", obsMeta(msi == null ? null : msi.get("asOf") + " " + closeTime(), avail, Rules.MSI_VERSION, "직전 252거래일 백분위", msi != null && Boolean.TRUE.equals(msi.get("provisional")) ? "잠정: " + msi.get("provisionalReason") : null));
        obs.put("ois", obsMeta(close, avail, c.ruleVersion, "점수 산출 항목별 기준일은 scores_json 참조", null));
        Map<String, Object> scores = new LinkedHashMap<>();
        scores.put("oisDisplay", c.items.isEmpty() ? null : c.toMap().get("display"));
        scores.put("oisItems", c.items.stream().map(ScoreItem::toMap).toList());
        scores.put("scenario", c.info.get("scenario"));
        scores.put("declineType", c.info.get("declineType"));
        if (msi != null) {
            Map<String, Object> mm = new LinkedHashMap<>();
            for (String k : new String[]{"score", "band", "dynamic", "ch1", "ch5", "ch20", "W", "confidence", "provisional", "areas"}) mm.put(k, msi.get(k));
            scores.put("msi", mm);
        }
        String sql = "INSERT OR IGNORE INTO paper_signals(signal_key, run_id, mode, strategy_version, params_json, market, account, symbol, name, signal_date, side, action, stage, decision, reason," +
                " msi, msi_band, msi_dynamic, msi_coverage, ois_s, ois_w, ois_coverage, ois_status, ois_confidence, scores_json, prev_close, avg_vol20, avg_val20, rel_vol, atr14, atr_pct, mkt_ret5, mkt_ret20," +
                " sector_ret20, planned_amount, planned_qty, liquidity_pre, obs_json, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        synchronized (db) {
            try (PreparedStatement ps = db.prepare(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                int i = 1;
                ps.setString(i++, key); ps.setLong(i++, runId); ps.setString(i++, mode); ps.setString(i++, VERSION);
                Map<String, Object> pm = prm.toMap();
                pm.put("versionDesc", VERSION_DESC);
                ps.setString(i++, Json.write(pm));
                ps.setString(i++, market); ps.setString(i++, account); ps.setString(i++, c.symbol); ps.setString(i++, c.name); ps.setString(i++, ctx.asOf.toString());
                ps.setString(i++, side); ps.setString(i++, action); ps.setObject(i++, stage); ps.setString(i++, decision); ps.setString(i++, reason);
                setD(ps, i++, msiScore(msi)); ps.setString(i++, msi == null ? null : (String) msi.get("band")); ps.setString(i++, msi == null ? null : (String) msi.get("dynamic"));
                setD(ps, i++, msi == null || msi.get("W") == null ? null : ((Number) msi.get("W")).doubleValue());
                boolean scored = !c.items.isEmpty();
                setD(ps, i++, scored ? c.S() : null); setD(ps, i++, scored ? c.W() : null); setD(ps, i++, scored ? c.coverage() : null);
                ps.setString(i++, c.status); ps.setString(i++, c.confidence); ps.setString(i++, Json.write(scores));
                setD(ps, i++, prevClose); setD(ps, i++, avgVol); setD(ps, i++, avgVal); setD(ps, i++, relVol); setD(ps, i++, atrAbs); setD(ps, i++, atrPct);
                setD(ps, i++, Indicators.ret(bm, 5)); setD(ps, i++, Indicators.ret(bm, 20)); setD(ps, i++, sect);
                setD(ps, i++, planned); setD(ps, i++, plannedQty); setD(ps, i++, liq); ps.setString(i++, Json.write(obs)); ps.setString(i++, now);
                if (ps.executeUpdate() == 0) return null;
                try (var r = ps.getGeneratedKeys()) { return r.next() ? r.getLong(1) : null; }
            }
        }
    }

    private boolean order(long sid, ScoreCard c, String side, String action, Integer stage, Double planned, Double plannedQty) throws SQLException {
        LocalDate t = nextTradingDay(ctx.asOf);
        try (PreparedStatement ps = db.prepare("INSERT OR IGNORE INTO paper_orders(signal_id, run_id, mode, strategy_version, account, market, symbol, name, side, stage, action," +
                " planned_amount, planned_qty, status, signal_date, target_date, status_reason, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?)")) {
            int i = 1;
            ps.setLong(i++, sid); ps.setLong(i++, runId); ps.setString(i++, mode); ps.setString(i++, VERSION); ps.setString(i++, account); ps.setString(i++, market);
            ps.setString(i++, c.symbol); ps.setString(i++, c.name); ps.setString(i++, side); ps.setObject(i++, stage); ps.setString(i++, action);
            setD(ps, i++, planned); setD(ps, i++, plannedQty); ps.setString(i++, ctx.asOf.toString()); ps.setString(i++, t == null ? null : t.toString());
            ps.setString(i++, "다음 거래일 시가 체결 대기" + (t == null ? "(다음 거래일 미확정)" : "")); ps.setString(i++, now); ps.setString(i++, now);
            return ps.executeUpdate() > 0;
        }
    }

    // ---------------- 일별 평가 ----------------
    /** 거래가 없는 날을 포함해 지난 평가일 이후 모든 거래일의 계좌 평가액을 원장(체결·입출금)에서 재구성. */
    private int equity() throws Exception {
        List<Map<String, Object>> acc = db.query("SELECT created_as_of FROM paper_accounts WHERE account=?", account);
        LocalDate created = LocalDate.parse((String) acc.get(0).get("created_as_of"));
        List<Map<String, Object>> last = db.query("SELECT MAX(date) d FROM paper_equity_daily WHERE account=? AND date<?", account, ctx.asOf.toString());
        LocalDate from = last.get(0).get("d") == null ? created : LocalDate.parse((String) last.get(0).get("d")).plusDays(1);
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d : cal) if (!d.isBefore(from) && !d.isBefore(created) && !d.isAfter(ctx.asOf)) days.add(d);
        List<Map<String, Object>> fills = db.query("SELECT symbol, side, qty, net_cash, realized_pnl, fill_date, fill_price FROM paper_fills WHERE account=? ORDER BY fill_date, id", account);
        List<Map<String, Object>> flows = db.query("SELECT date, amount FROM paper_cashflows WHERE account=?", account);
        String bench = "KR".equals(market) ? "KOSPI" : "SPX";
        List<Bar> bm = ctx.index.getOrDefault(bench, List.of());
        List<Bar> fx = p.fxSeries(ctx.asOf).getOrDefault("USDKRW", List.of());
        int n = 0;
        for (LocalDate d : days) {
            String ds = d.toString();
            double dep = 0, cash = 0, realized = 0;
            for (Map<String, Object> f : flows) if (((String) f.get("date")).compareTo(ds) <= 0) dep += ((Number) f.get("amount")).doubleValue();
            cash = dep;
            Map<String, Double> qty = new LinkedHashMap<>();
            Map<String, Double> lastFill = new LinkedHashMap<>();
            for (Map<String, Object> f : fills) {
                if (((String) f.get("fill_date")).compareTo(ds) > 0) continue;
                String sym = (String) f.get("symbol");
                double q = ((Number) f.get("qty")).doubleValue() * ("BUY".equals(f.get("side")) ? 1 : -1);
                qty.merge(sym, q, Double::sum);
                cash += ((Number) f.get("net_cash")).doubleValue();
                realized += ((Number) f.get("realized_pnl")).doubleValue();
                lastFill.put(sym, ((Number) f.get("fill_price")).doubleValue());
            }
            double value = 0;
            List<String> notes = new ArrayList<>();
            for (Map.Entry<String, Double> e : qty.entrySet()) {
                if (e.getValue() <= 1e-9) continue;
                Bar cb = null;
                for (Bar x : ctx.bars.getOrDefault(e.getKey(), List.of())) { if (x.date().isAfter(d)) break; cb = x; }
                double px;
                if (cb != null) { px = cb.close(); if (!cb.date().equals(d)) notes.add(e.getKey() + " 종가 " + cb.date() + " 기준(당일 없음)"); }
                else { px = lastFill.get(e.getKey()); notes.add(e.getKey() + " 종가 미확보 — 체결가로 평가"); }
                value += e.getValue() * px;
            }
            double eq = cash + value;
            Double fxr = 1.0;
            String fxDate = ds, fxSrc = "KRW 계좌(환산 없음)";
            if ("US".equals(market)) {
                fxr = null; fxDate = null; fxSrc = p.fxSourceLabel();
                for (Bar x : fx) { if (x.date().isAfter(d)) break; fxr = x.close(); fxDate = x.date().toString(); }
                if (fxr == null) notes.add("환율 미확보 — 원화 환산 결측");
            }
            Double bc = Tracker.closeOn(bm, d);
            try (PreparedStatement ps = db.prepare("INSERT OR REPLACE INTO paper_equity_daily(account, date, market, currency, cash, positions_value, equity, deposits_cum, realized_cum," +
                    " unrealized, n_fills, n_unfilled, n_deferred, benchmark, bench_close, fx_rate, fx_date, fx_source, equity_krw, valuation_note, run_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int i = 1;
                ps.setString(i++, account); ps.setString(i++, ds); ps.setString(i++, market); ps.setString(i++, currency);
                ps.setDouble(i++, cash); ps.setDouble(i++, value); ps.setDouble(i++, eq); ps.setDouble(i++, dep); ps.setDouble(i++, realized);
                ps.setDouble(i++, eq - dep - realized);
                ps.setInt(i++, db.count("SELECT COUNT(*) FROM paper_fills WHERE account=? AND fill_date=?", account, ds));
                ps.setInt(i++, db.count("SELECT COUNT(*) FROM paper_orders WHERE account=? AND status='UNFILLED' AND target_date=?", account, ds));
                ps.setInt(i++, db.count("SELECT COUNT(*) FROM paper_signals WHERE account=? AND decision='DEFER' AND signal_date=?", account, ds));
                ps.setString(i++, bench); setD(ps, i++, bc); setD(ps, i++, fxr); ps.setString(i++, fxDate); ps.setString(i++, fxSrc);
                setD(ps, i++, fxr == null ? null : eq * fxr);
                ps.setString(i++, notes.isEmpty() ? (qty.values().stream().allMatch(v -> v <= 1e-9) ? "보유 없음" : "종가 평가") : String.join("; ", notes));
                ps.setLong(i++, runId);
                ps.executeUpdate();
            }
            n++;
        }
        return n;
    }

    // ---------------- 성과 분해 ----------------
    /** 매수 체결별 최신 종가 기준 비용 차감 후 수익률(사후 평가)을 갱신하고 시장·심리 구간·단계·갭·유동성 구간별로 집계. */
    private void performance() throws Exception {
        double sellCost = prm.feeSell + prm.taxSell + prm.slipBps / 10000.0;
        List<Map<String, Object>> buys = db.query("SELECT f.id, f.symbol, f.fill_price, f.fee, f.qty, f.gap, f.stage, s.msi_band, s.liquidity_pre FROM paper_fills f" +
                " JOIN paper_signals s ON s.id=f.signal_id WHERE f.account=? AND f.side='BUY'", account);
        Map<String, List<Double>> groups = new LinkedHashMap<>();
        for (Map<String, Object> f : buys) {
            List<Bar> b = ctx.bars.get((String) f.get("symbol"));
            if (b == null || b.isEmpty()) continue;
            Bar last = b.get(b.size() - 1);
            double fp = ((Number) f.get("fill_price")).doubleValue(), q = ((Number) f.get("qty")).doubleValue(), fee = ((Number) f.get("fee")).doubleValue();
            double cost = fp * q + fee;
            double r = (last.close() * q * (1 - sellCost)) / cost - 1;
            try (PreparedStatement ps = db.prepare("UPDATE paper_fills SET mark_date=?, mark_price=?, net_ret_mark=? WHERE id=?")) {
                ps.setString(1, last.date().toString()); ps.setDouble(2, last.close()); ps.setDouble(3, r); ps.setLong(4, ((Number) f.get("id")).longValue());
                ps.executeUpdate();
            }
            Double gap = f.get("gap") == null ? null : ((Number) f.get("gap")).doubleValue();
            Double liq = f.get("liquidity_pre") == null ? null : ((Number) f.get("liquidity_pre")).doubleValue();
            add(groups, "시장|" + market, r);
            add(groups, "심리 구간|" + (f.get("msi_band") == null ? "미산출" : f.get("msi_band")), r);
            add(groups, "분할 단계|" + f.get("stage") + "차", r);
            add(groups, "시가 갭|" + (gap == null ? "결측" : gap < -0.02 ? "< -2%" : gap < 0 ? "-2%~0" : gap < 0.02 ? "0~+2%" : "≥ +2%"), r);
            add(groups, "유동성(계획수량/20일 평균거래량)|" + (liq == null ? "결측" : liq < 0.0001 ? "< 0.01%" : liq < 0.001 ? "0.01~0.1%" : "≥ 0.1%"), r);
        }
        try (PreparedStatement del = db.prepare("DELETE FROM paper_performance WHERE as_of=? AND market=? AND strategy_version=?")) {
            del.setString(1, ctx.asOf.toString()); del.setString(2, market); del.setString(3, VERSION); del.executeUpdate();
        }
        for (Map.Entry<String, List<Double>> e : groups.entrySet()) {
            String[] k = e.getKey().split("\\|", 2);
            List<Double> v = e.getValue();
            try (PreparedStatement ps = db.prepare("INSERT OR REPLACE INTO paper_performance(as_of, market, strategy_version, dimension, bucket, n, mean_net_ret, median_net_ret, win_rate, note) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, ctx.asOf.toString()); ps.setString(2, market); ps.setString(3, VERSION); ps.setString(4, k[0]); ps.setString(5, k[1]);
                ps.setInt(6, v.size()); ps.setDouble(7, Num.mean(v)); ps.setDouble(8, Num.median(v));
                ps.setDouble(9, v.stream().filter(x -> x > 0).count() / (double) v.size());
                ps.setString(10, "매수 체결별 최신 종가 평가(매도 비용 가정 차감, 사후). 표본 " + v.size() + "건" + (v.size() < 30 ? " — 30건 미만, 해석 주의" : ""));
                ps.executeUpdate();
            }
        }
    }

    private static void add(Map<String, List<Double>> g, String k, double v) { g.computeIfAbsent(k, x -> new ArrayList<>()).add(v); }

    public static void logSummary(String m, Map<String, Object> s) { Log.info("[" + m + "] 모의매매: " + Json.write(s)); }
}
