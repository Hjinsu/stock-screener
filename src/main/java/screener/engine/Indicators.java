package screener.engine;

import screener.model.Bar;
import screener.util.Num;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** data-and-scoring.md 의 공통 산식. 모든 함수는 마지막 원소를 기준 시점(t)으로 본다. 자료 부족 시 null. */
public final class Indicators {
    private Indicators() { }

    /** C[t]/C[t-n]-1. n일 수익률에는 n+1개 종가 필요. */
    public static Double ret(List<Bar> b, int n) {
        if (b.size() < n + 1) return null;
        double c0 = b.get(b.size() - 1 - n).close(), c1 = b.get(b.size() - 1).close();
        return c0 > 0 ? c1 / c0 - 1 : null;
    }

    /** 최근 n개 평균 거래대금. */
    public static Double avgValue(List<Bar> b, int n) {
        if (b.size() < n) return null;
        double s = 0;
        for (int i = b.size() - n; i < b.size(); i++) s += b.get(i).tradeValue();
        return s / n;
    }

    /** 최근 recent개 평균 / 그 직전 base개 평균 (field: 0=거래량, 1=거래대금). */
    public static Double recentVsPrior(List<Bar> b, int recent, int base, int field) {
        if (b.size() < recent + base) return null;
        double r = 0, p = 0;
        int n = b.size();
        for (int i = n - recent; i < n; i++) r += field == 0 ? b.get(i).volume() : b.get(i).tradeValue();
        for (int i = n - recent - base; i < n - recent; i++) p += field == 0 ? b.get(i).volume() : b.get(i).tradeValue();
        r /= recent;
        p /= base;
        return p > 0 ? r / p : null;
    }

    /** 오늘 값/직전 20개 완료 세션 평균(오늘 제외). idx는 b 내 위치. */
    public static Double volMultipleAt(List<Bar> b, int idx, int base) {
        if (idx - base < 0) return null;
        double p = 0;
        for (int i = idx - base; i < idx; i++) p += b.get(i).volume();
        p /= base;
        return p > 0 ? b.get(idx).volume() / p : null;
    }

    /** ATR14/종가 시계열(단순 평균 TR). 결과 길이 = b.size()-14 (index 14부터). */
    public static List<Double> atrPct(List<Bar> b, int period) {
        List<Double> out = new ArrayList<>();
        if (b.size() < period + 1) return out;
        List<Double> tr = new ArrayList<>();
        for (int i = 1; i < b.size(); i++) {
            Bar x = b.get(i);
            double pc = b.get(i - 1).close();
            tr.add(Math.max(x.high() - x.low(), Math.max(Math.abs(x.high() - pc), Math.abs(x.low() - pc))));
        }
        for (int i = period - 1; i < tr.size(); i++) {
            double s = 0;
            for (int k = i - period + 1; k <= i; k++) s += tr.get(k);
            double c = b.get(i + 1).close();
            out.add(c > 0 ? (s / period) / c : Double.NaN);
        }
        return out;
    }

    /** 볼린저밴드 폭(20일, 2σ)/중심선 시계열. */
    public static List<Double> bbWidth(List<Bar> b, int period) {
        List<Double> out = new ArrayList<>();
        for (int i = period - 1; i < b.size(); i++) {
            List<Double> w = new ArrayList<>();
            for (int k = i - period + 1; k <= i; k++) w.add(b.get(k).close());
            double m = Num.mean(w);
            double sd = popStd(w, m);
            out.add(m > 0 ? (4 * sd) / m : Double.NaN);
        }
        return out;
    }

    private static double popStd(List<Double> w, double m) {
        double s = 0;
        for (double d : w) s += (d - m) * (d - m);
        return Math.sqrt(s / w.size());
    }

    /** 시계열 마지막 recent개 평균 / 그 직전 base개 평균. */
    public static Double seriesRecentVsPrior(List<Double> s, int recent, int base) {
        if (s.size() < recent + base) return null;
        int n = s.size();
        double r = 0, p = 0;
        for (int i = n - recent; i < n; i++) r += s.get(i);
        for (int i = n - recent - base; i < n - recent; i++) p += s.get(i);
        r /= recent;
        p /= base;
        return p > 0 ? r / p : null;
    }

    /** 최근 60세션 최고 종가 대비 낙폭: C[t]/max(C)-1. */
    public static Double drawdown(List<Bar> b, int n) {
        if (b.size() < n) return null;
        double mx = 0;
        for (int i = b.size() - n; i < b.size(); i++) mx = Math.max(mx, b.get(i).close());
        return mx > 0 ? b.get(b.size() - 1).close() / mx - 1 : null;
    }

    /** 이동평균. */
    public static Double sma(List<Bar> b, int n) {
        if (b.size() < n) return null;
        double s = 0;
        for (int i = b.size() - n; i < b.size(); i++) s += b.get(i).close();
        return s / n;
    }

    /** 일별 수익률 표준편차(실현 변동성, 비연율화). */
    public static Double realizedVol(List<Bar> b, int n) {
        if (b.size() < n + 1) return null;
        List<Double> r = new ArrayList<>();
        for (int i = b.size() - n; i < b.size(); i++) r.add(b.get(i).close() / b.get(i - 1).close() - 1);
        return Num.stdev(r);
    }

    /** 날짜 ≤ d 인 구간만 반환(미래 자료 차단). */
    public static List<Bar> upTo(List<Bar> b, LocalDate d) {
        int hi = b.size();
        while (hi > 0 && b.get(hi - 1).date().isAfter(d)) hi--;
        return b.subList(0, hi);
    }

    /** 최근 n개 바 범위에서 상장주식수가 1% 이상 변하면 true(분할·병합·증자 등으로 원주가 불연속 가능). */
    public static boolean sharesChanged(List<Bar> b, int n) {
        Double first = null;
        for (int i = Math.max(0, b.size() - n); i < b.size(); i++) {
            Double s = b.get(i).shares();
            if (s == null || s <= 0) continue;
            if (first == null) first = s;
            else if (Math.abs(s / first - 1) > 0.01) return true;
        }
        return false;
    }

    /** P=100×(현재보다 작은 비교값 수+0.5×동일값 수)/비교값 수. */
    public static Double percentile(List<Double> comparisons, double current) {
        if (comparisons.isEmpty()) return null;
        int lt = 0, eq = 0;
        for (double c : comparisons) {
            if (c < current) lt++;
            else if (c == current) eq++;
        }
        return 100.0 * (lt + 0.5 * eq) / comparisons.size();
    }
}
