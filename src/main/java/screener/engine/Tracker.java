package screener.engine;

import screener.model.Bar;
import screener.store.Db;
import screener.util.Num;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 최초 탐지 후 5·10·20·40·60거래일 추적. 체크포인트는 (추적ID, 기간) 고유키로 한 번만 기록된다.
 * 각 체크포인트는 해당 시점까지의 자료만 사용(미래 자료 차단). 60거래일에 추적 종료.
 */
public final class Tracker {
    public static final int[] HORIZONS = {5, 10, 20, 40, 60};

    private Tracker() { }

    public static int update(Db db, String model, MarketContext ctx, List<String> notes) throws Exception {
        int added = 0;
        for (Map<String, Object> t : db.trackingsFor(model, ctx.market, ctx.asOf.toString())) {
            long id = ((Number) t.get("id")).longValue();
            String sym = (String) t.get("symbol");
            List<Bar> b = ctx.bars.get(sym);
            if (b == null || b.isEmpty()) { notes.add(model + " 추적 " + sym + ": 일봉 미확보로 이번 실행 갱신 불가"); continue; }
            LocalDate d0 = LocalDate.parse((String) t.get("detect_date"));
            int i0 = -1;
            for (int k = 0; k < b.size(); k++) if (b.get(k).date().equals(d0)) { i0 = k; break; }
            if (i0 < 0) { notes.add(model + " 추적 " + sym + ": 탐지일 봉 없음"); continue; }
            double c0 = ((Number) t.get("detect_close")).doubleValue();
            int elapsed = b.size() - 1 - i0;
            List<Bar> bm = ctx.index.get((String) t.get("bench"));
            String st = db.state(model, ctx.market, sym) == null ? null : db.state(model, ctx.market, sym).state();
            if ("INVALIDATED".equals(st) || "EXCLUDED_RISK".equals(st)) {
                db.setInvalidReason(id, Rules.label(st) + " (" + ctx.asOf + ")");
            }
            for (int h : HORIZONS) {
                if (elapsed < h) break;
                Bar bh = b.get(i0 + h);
                double mx = Double.NEGATIVE_INFINITY, mn = Double.POSITIVE_INFINITY;
                for (int k = i0 + 1; k <= i0 + h; k++) { mx = Math.max(mx, b.get(k).high()); mn = Math.min(mn, b.get(k).low()); }
                double ret = bh.close() / c0 - 1, mfe = mx / c0 - 1, mae = mn / c0 - 1;
                Double br = benchRet(bm, d0, bh.date());
                Double ex = br == null ? null : ret - br;
                Double sx = sectorExcess(ctx, sym, d0, bh.date(), ret);
                String ds = bh.date().toString();
                if (db.addCheckpoint(id, h, ds, bh.close(), ret, mfe, mae, br, ex, sx, db.scoreOn(model, ctx.market, sym, ds), db.stateOn(model, ctx.market, sym, ds))) added++;
            }
            if (elapsed >= 60) {
                if ("ACTIVE".equals(t.get("status"))) db.endTracking(id, "60거래일 추적 종료 — 최종 평가: 60일 수익률/MFE/MAE 체크포인트 참조", ctx.asOf.toString());
                Db.StateRow sr = db.state(model, ctx.market, sym);
                if (sr != null && !"ENDED".equals(sr.state())) {
                    double s = db.scoreOn(model, ctx.market, sym, ctx.asOf.toString()) == null ? 0 : db.scoreOn(model, ctx.market, sym, ctx.asOf.toString());
                    db.setState(model, ctx.market, sym, (String) t.get("name"), sr.state(), "ENDED", ctx.asOf.toString(), s, "60거래일 추적 종료");
                }
            }
        }
        return added;
    }

    static Double benchRet(List<Bar> bm, LocalDate d0, LocalDate d1) {
        if (bm == null) return null;
        Double a = closeOn(bm, d0), b = closeOn(bm, d1);
        return a == null || b == null || a <= 0 ? null : b / a - 1;
    }

    static Double closeOn(List<Bar> b, LocalDate d) {
        for (int k = b.size() - 1; k >= 0; k--) if (!b.get(k).date().isAfter(d)) return b.get(k).date().equals(d) ? b.get(k).close() : null;
        return null;
    }

    static Double sectorExcess(MarketContext ctx, String sym, LocalDate d0, LocalDate d1, double ret) {
        var s = ctx.secs.get(sym);
        if (s == null || s.sector == null) return null;
        List<Double> rs = new ArrayList<>();
        for (MarketContext.PeerStat p : ctx.peersOf(s)) {
            List<Bar> pb = ctx.bars.get(p.symbol());
            if (pb == null) continue;
            Double r = benchRet(pb, d0, d1);
            if (r != null) rs.add(r);
        }
        return rs.size() < 4 ? null : ret - Num.median(rs);
    }
}
