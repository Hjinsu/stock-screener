package screener.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Num {
    private Num() { }

    /** "1,234", "+3,611,532", "46.07%", "$355.63" 형태를 숫자로. 실패 시 null. */
    public static Double parse(String s) {
        if (s == null) return null;
        String t = s.trim().replace(",", "").replace("%", "").replace("$", "").replace("+", "");
        if (t.isEmpty() || t.equals("-") || t.equalsIgnoreCase("N/A") || t.equalsIgnoreCase("null")) return null;
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
    }

    public static double median(List<Double> v) {
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        int n = c.size();
        if (n == 0) return Double.NaN;
        return n % 2 == 1 ? c.get(n / 2) : (c.get(n / 2 - 1) + c.get(n / 2)) / 2.0;
    }

    public static double mean(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        double s = 0;
        for (double d : v) s += d;
        return s / v.size();
    }

    public static double stdev(List<Double> v) {
        if (v.size() < 2) return Double.NaN;
        double m = mean(v), s = 0;
        for (double d : v) s += (d - m) * (d - m);
        return Math.sqrt(s / (v.size() - 1));
    }

    public static String pct(Double d) {
        if (d == null || d.isNaN()) return "-";
        return String.format("%+.2f%%", d * 100);
    }

    public static String f2(Double d) {
        if (d == null || d.isNaN()) return "-";
        return String.format("%.2f", d);
    }

    /** 한국어 큰 금액 표기: 원 단위 → 조/억. */
    public static String krw(Double won) {
        if (won == null || won.isNaN()) return "-";
        double a = Math.abs(won);
        String sign = won < 0 ? "-" : "";
        if (a >= 1e12) return sign + String.format("%.2f조원", a / 1e12);
        if (a >= 1e8) return sign + String.format("%.1f억원", a / 1e8);
        return sign + String.format("%.0f원", a);
    }

    public static String usd(Double v) {
        if (v == null || v.isNaN()) return "-";
        double a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a >= 1e9) return sign + String.format("$%.2fB", a / 1e9);
        if (a >= 1e6) return sign + String.format("$%.1fM", a / 1e6);
        return sign + String.format("$%.2f", a);
    }

    public static String money(String market, Double v) { return "KR".equals(market) ? krw(v) : usd(v); }
}
