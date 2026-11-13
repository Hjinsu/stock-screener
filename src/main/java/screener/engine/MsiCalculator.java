package screener.engine;

import screener.model.Bar;
import screener.model.FlowDay;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 시장심리지수(US-MSI / KR-MSI). 각 지표를 직전 252거래일(최소 120개) 대비 백분위로 정규화하고,
 * 같은 개념은 그룹으로 묶어 평균, 영역 점수의 가중합/W 로 산출한다(W≥75 & 가격·확산도 모두 있을 때만).
 */
public final class MsiCalculator {
    public static final int LOOKBACK = 252, MIN_COMP = 120;

    public static final class Ind {
        final String area, group, name, source;
        final int dir;
        final boolean substitute;
        boolean direct; // 0~100 직접 점수(뉴스 7단계 매핑) — 백분위 정규화 미적용
        final Map<LocalDate, Double> series;

        Ind(String area, String group, String name, int dir, boolean substitute, String source, Map<LocalDate, Double> series) {
            this.area = area; this.group = group; this.name = name; this.dir = dir; this.substitute = substitute; this.source = source; this.series = series;
        }
    }

    private final String market;
    private final List<LocalDate> cal;            // 거래일 달력(오름차순)
    private final Map<String, Integer> weights = new LinkedHashMap<>();
    private final List<Ind> inds = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    public MsiCalculator(String market, List<LocalDate> calendar) {
        this.market = market;
        this.cal = calendar;
        if ("US".equals(market)) {
            weights.put("가격·변동성", 35); weights.put("시장 확산도", 25); weights.put("수급·파생", 15); weights.put("뉴스·커뮤니티", 25);
        } else {
            weights.put("가격·변동성", 30); weights.put("시장 확산도", 20); weights.put("수급·파생", 25); weights.put("뉴스·커뮤니티", 25);
        }
    }

    public void note(String n) { notes.add(n); }

    public void add(String area, String group, String name, int dir, boolean substitute, String source, Map<LocalDate, Double> series) {
        if (series != null && !series.isEmpty()) inds.add(new Ind(area, group, name, dir, substitute, source, series));
        else notes.add(name + ": 시계열 미확보(" + source + ")");
    }

    /** 이미 0~100으로 매핑된 점수(뉴스·커뮤니티 7단계 매핑)를 그대로 영역 점수로 사용. */
    public void addDirect(String area, String group, String name, boolean substitute, String source, Map<LocalDate, Double> series) {
        if (series != null && !series.isEmpty()) {
            Ind i = new Ind(area, group, name, 1, substitute, source, series);
            i.direct = true;
            inds.add(i);
        } else notes.add(name + ": 기준일 점수 없음(" + source + ")");
    }

    // ---------- 시계열 생성 도우미 ----------
    public static Map<LocalDate, Double> returns(List<Bar> b, int n) {
        Map<LocalDate, Double> m = new HashMap<>();
        for (int i = n; i < b.size(); i++) if (b.get(i - n).close() > 0) m.put(b.get(i).date(), b.get(i).close() / b.get(i - n).close() - 1);
        return m;
    }

    public static Map<LocalDate, Double> closes(List<Bar> b) {
        Map<LocalDate, Double> m = new HashMap<>();
        for (Bar x : b) m.put(x.date(), x.close());
        return m;
    }

    public static Map<LocalDate, Double> ratio(List<Bar> a, List<Bar> b) {
        Map<LocalDate, Double> mb = closes(b), m = new HashMap<>();
        for (Bar x : a) { Double y = mb.get(x.date()); if (y != null && y > 0) m.put(x.date(), x.close() / y); }
        return m;
    }

    public static Map<LocalDate, Double> realizedVol(List<Bar> b, int n) {
        Map<LocalDate, Double> m = new HashMap<>();
        for (int i = n; i < b.size(); i++) {
            List<Double> r = new ArrayList<>();
            for (int k = i - n + 1; k <= i; k++) r.add(b.get(k).close() / b.get(k - 1).close() - 1);
            m.put(b.get(i).date(), screener.util.Num.stdev(r));
        }
        return m;
    }

    /** 유니버스 상승 종목 비율(5일 평균). */
    public static Map<LocalDate, Double> advanceRatio(Map<String, List<Bar>> uni, int minCount) {
        Map<LocalDate, int[]> cnt = new TreeMap<>();
        for (List<Bar> b : uni.values()) {
            for (int i = 1; i < b.size(); i++) {
                int[] c = cnt.computeIfAbsent(b.get(i).date(), k -> new int[2]);
                c[1]++;
                if (b.get(i).close() > b.get(i - 1).close()) c[0]++;
            }
        }
        List<LocalDate> ds = new ArrayList<>(cnt.keySet());
        Map<LocalDate, Double> daily = new HashMap<>(), out = new HashMap<>();
        for (LocalDate d : ds) { int[] c = cnt.get(d); if (c[1] >= minCount) daily.put(d, (double) c[0] / c[1]); }
        for (int i = 4; i < ds.size(); i++) {
            double s = 0; int n = 0;
            for (int k = i - 4; k <= i; k++) { Double v = daily.get(ds.get(k)); if (v != null) { s += v; n++; } }
            if (n == 5) out.put(ds.get(i), s / 5);
        }
        return out;
    }

    /** 52주 신고가 비중 − 신저가 비중(종가 기준, 252세션 필요). */
    public static Map<LocalDate, Double> highLow(Map<String, List<Bar>> uni, int minCount) {
        Map<LocalDate, int[]> cnt = new HashMap<>();
        for (List<Bar> b : uni.values()) {
            for (int i = 251; i < b.size(); i++) {
                double mx = -1, mn = Double.MAX_VALUE;
                for (int k = i - 251; k <= i; k++) { double c = b.get(k).close(); if (c > mx) mx = c; if (c < mn) mn = c; }
                int[] c = cnt.computeIfAbsent(b.get(i).date(), k -> new int[3]);
                c[2]++;
                double cl = b.get(i).close();
                if (cl >= mx) c[0]++;
                if (cl <= mn) c[1]++;
            }
        }
        Map<LocalDate, Double> m = new HashMap<>();
        cnt.forEach((d, c) -> { if (c[2] >= minCount) m.put(d, (double) (c[0] - c[1]) / c[2]); });
        return m;
    }

    /** 상위 K종목 외국인(who=0)·기관(who=1) 5일 순매수 금액 합 / 5일 거래대금 합. */
    public static Map<LocalDate, Double> flowRatio(Map<String, List<FlowDay>> flows, Map<String, List<Bar>> bars, int who, int minStocks) {
        Map<LocalDate, double[]> agg = new TreeMap<>();
        for (Map.Entry<String, List<FlowDay>> e : flows.entrySet()) {
            Map<LocalDate, Double> tv = new HashMap<>();
            for (Bar b : bars.getOrDefault(e.getKey(), List.of())) tv.put(b.date(), b.tradeValue());
            for (FlowDay f : e.getValue()) {
                Double q = who == 0 ? f.foreignQty() : f.instQty();
                Double v = tv.get(f.date());
                if (q == null || v == null) continue;
                double[] a = agg.computeIfAbsent(f.date(), k -> new double[3]);
                a[0] += q * f.close();
                a[1] += v;
                a[2]++;
            }
        }
        List<LocalDate> ds = new ArrayList<>(agg.keySet());
        Map<LocalDate, Double> m = new HashMap<>();
        for (int i = 4; i < ds.size(); i++) {
            double n = 0, v = 0; boolean ok = true;
            for (int k = i - 4; k <= i; k++) { double[] a = agg.get(ds.get(k)); if (a[2] < minStocks) ok = false; n += a[0]; v += a[1]; }
            if (ok && v > 0) m.put(ds.get(i), n / v);
        }
        return m;
    }

    // ---------- 계산 ----------
    private Double indScore(Ind ind, int ti) {
        LocalDate t = cal.get(ti);
        Double v = ind.series.get(t);
        if (v == null || v.isNaN()) return null;
        if (ind.direct) return Math.max(0, Math.min(100, v));
        List<Double> comp = new ArrayList<>();
        for (int k = Math.max(0, ti - LOOKBACK); k < ti; k++) {
            Double x = ind.series.get(cal.get(k));
            if (x != null && !x.isNaN()) comp.add(x);
        }
        if (comp.size() < MIN_COMP) return null;
        double p = Indicators.percentile(comp, v);
        return ind.dir > 0 ? p : 100 - p;
    }

    /** 지정한 지표 집합(names)으로 ti 시점 점수. names=null이면 가능한 모든 지표. 반환 [score, W, detail] */
    private Object[] scoreAt(int ti, java.util.Set<String> names, boolean detail) {
        Map<String, Map<String, List<Double>>> byArea = new LinkedHashMap<>();
        List<Map<String, Object>> indRows = new ArrayList<>();
        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        for (Ind ind : inds) {
            if (names != null && !names.contains(ind.name)) continue;
            Double sc = indScore(ind, ti);
            if (detail) {
                Map<String, Object> r = new LinkedHashMap<>();
                LocalDate t = cal.get(ti);
                r.put("area", ind.area); r.put("group", ind.group); r.put("name", ind.name);
                r.put("value", ind.series.get(t)); r.put("score", sc); r.put("direction", ind.dir > 0 ? "+" : "−");
                r.put("substitute", ind.substitute); r.put("source", ind.source);
                int n = 0;
                for (int k = Math.max(0, ti - LOOKBACK); k < ti; k++) if (ind.series.get(cal.get(k)) != null) n++;
                r.put("comparisons", ind.direct ? 0 : n);
                if (ind.direct) r.put("note", sc == null ? "기준일 점수 없음(독립 출처 부족·미수집)" : "7단계 매핑 직접 점수(백분위 미적용)");
                else if (sc == null) r.put("note", ind.series.get(t) == null ? "기준일 값 없음" : "비교값 " + n + "개 < " + MIN_COMP);
                indRows.add(r);
            }
            if (sc == null) { if (names != null) return null; continue; }
            used.add(ind.name);
            byArea.computeIfAbsent(ind.area, k -> new LinkedHashMap<>()).computeIfAbsent(ind.group, k -> new ArrayList<>()).add(sc);
        }
        double sum = 0, W = 0;
        List<Map<String, Object>> areaRows = new ArrayList<>();
        boolean hasPrice = false, hasBreadth = false;
        for (Map.Entry<String, Integer> w : weights.entrySet()) {
            Map<String, List<Double>> groups = byArea.get(w.getKey());
            Map<String, Object> ar = new LinkedHashMap<>();
            ar.put("area", w.getKey()); ar.put("weight", w.getValue());
            if (groups == null || groups.isEmpty()) { ar.put("score", null); ar.put("evaluated", false); areaRows.add(ar); continue; }
            double gs = 0;
            for (List<Double> g : groups.values()) gs += g.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double as = gs / groups.size();
            ar.put("score", Math.round(as * 10) / 10.0); ar.put("evaluated", true);
            areaRows.add(ar);
            sum += as * w.getValue();
            W += w.getValue();
            if (w.getKey().equals("가격·변동성")) hasPrice = true;
            if (w.getKey().equals("시장 확산도")) hasBreadth = true;
        }
        Double score = W >= 75 && hasPrice && hasBreadth ? sum / W : null;
        return new Object[]{score, W, areaRows, indRows, used};
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> compute(LocalDate asOf) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("market", market);
        out.put("name", "US".equals(market) ? "US-MSI" : "KR-MSI");
        out.put("asOf", asOf.toString());
        out.put("ruleVersion", Rules.MSI_VERSION);
        int ti = cal.indexOf(asOf);
        if (ti < 0) {
            out.put("score", null);
            out.put("reason", "기준일이 지수 달력에 없음");
            out.put("notes", notes);
            return out;
        }
        Object[] r = scoreAt(ti, null, true);
        Double score = (Double) r[0];
        double W = (Double) r[1];
        List<Map<String, Object>> areaRows = (List<Map<String, Object>>) r[2];
        List<Map<String, Object>> indRows = (List<Map<String, Object>>) r[3];
        java.util.Set<String> used = (java.util.Set<String>) r[4];
        out.put("W", W);
        out.put("areas", areaRows);
        out.put("indicators", indRows);
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> a : areaRows) if (!(Boolean) a.get("evaluated")) missing.add(a.get("area") + " 미평가");
        long partial = indRows.stream().filter(x -> x.get("score") == null).count();
        long subs = inds.stream().filter(i -> used.contains(i.name) && i.substitute).count();
        boolean provisional = !missing.isEmpty() || partial > 0 || subs > 0;
        out.put("missing", missing);
        out.put("provisional", provisional);
        out.put("notes", notes);
        if (score == null) {
            out.put("score", null);
            out.put("reason", "평가 가능 배점 W=" + W + " (<75) 또는 가격·확산도 영역 누락 — 점수 미산출");
            out.put("confidence", "-");
            return out;
        }
        int rounded = (int) Math.round(score);
        out.put("score", Math.round(score * 10) / 10.0);
        out.put("rounded", rounded);
        out.put("band", Rules.msiBand(rounded));
        out.put("action", Rules.msiAction(rounded));
        String conf = !provisional ? "A" : (subs == 0 && partial == 0 && missing.size() == 1) ? "B" : "C";
        out.put("confidence", conf);
        out.put("provisionalReason", provisional ? String.join("; ", missing) + (partial > 0 ? "; 일부 지표 미산출 " + partial + "개" : "") + (subs > 0 ? "; 대체 지표 " + subs + "개" : "") : null);
        // 동일 구성 변화량
        Double c1 = change(ti, 1, used, score), c5 = change(ti, 5, used, score), c20 = change(ti, 20, used, score);
        out.put("ch1", c1); out.put("ch5", c5); out.put("ch20", c20);
        List<Map<String, Object>> hist = new ArrayList<>();
        for (int k = Math.max(0, ti - 24); k <= ti; k++) {
            Object[] h = scoreAt(k, used, false);
            if (h != null && h[0] != null) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("date", cal.get(k).toString());
                p.put("score", Math.round((Double) h[0] * 10) / 10.0);
                hist.add(p);
            }
        }
        out.put("history", hist);
        out.put("dynamic", dynamic(rounded, c1, c5));
        return out;
    }

    private Double change(int ti, int n, java.util.Set<String> used, double cur) {
        if (ti - n < 0) return null;
        Object[] p = scoreAt(ti - n, used, false);
        if (p == null || p[0] == null) return null;
        return Math.round((cur - (Double) p[0]) * 10) / 10.0;
    }

    /** 원본 02 §5 심리 변화 상태. 변화량이 없으면 미산출. */
    public static String dynamic(int s, Double c1, Double c5) {
        if (c5 == null) return null;
        double prev5 = s - c5;
        if (s <= 15) {
            if (c5 <= -5) return "공포 심화";
            if (c1 != null && c1 > 0) return "투매 진정";
            return "극단적 공포 지속";
        }
        if (s <= 45) {
            if (c5 <= -5) return "공포 심화";
            if (c5 >= 5) return prev5 <= 15 ? "투매 진정" : "공포 완화";
            return null;
        }
        if (s <= 60) return prev5 < 46 ? "중립 회복" : null;
        if (s <= 75) {
            if (prev5 <= 60) return "낙관 전환";
            if (c5 <= -5) return "탐욕 둔화";
            return null;
        }
        if (s <= 90) {
            if (c5 <= -5) return "탐욕 둔화";
            return "과도한 낙관";
        }
        if (c1 != null && c1 < 0) return "환희 정점 후보";
        return "과도한 낙관";
    }
}
