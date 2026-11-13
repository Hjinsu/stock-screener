package screener.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MAS 또는 OIS 한 종목의 점수표. 두 모델은 별도 객체/별도 행으로 저장되며 합산하지 않는다.
 * 표시 규칙: "확인 S/W점, 커버리지 W%, 전체 가능 범위 S~S+100−W".
 */
public final class ScoreCard {
    public final String model;       // MAS | OIS
    public final String ruleVersion;
    public final String market;
    public final String symbol;
    public String name;
    public String asOf;
    public final List<ScoreItem> items = new ArrayList<>();
    public final List<Map<String, Object>> filters = new ArrayList<>();
    public final List<Map<String, Object>> mandatory = new ArrayList<>();
    public final List<String> pros = new ArrayList<>();
    public final List<String> cons = new ArrayList<>();
    public final List<String> missing = new ArrayList<>();
    public final List<String> nextChecks = new ArrayList<>();
    public final Map<String, Object> info = new LinkedHashMap<>();
    public final List<Map<String, Object>> indirect = new ArrayList<>(); // 점수 미반영 간접 자료(13F/ATS 등)

    public String status;            // 상태 코드
    public String statusLabel;       // 한국어
    public String statusReason;
    public String gradeQualifier;    // 확정 / 잠정 / 하한 기준 / 미산출
    public String confidence;        // A/B/C
    public boolean filterPassed;     // 신규 등록 필터 통과(모두 확정 통과)
    public boolean filterUnconfirmed;
    public boolean mandatoryPassed;
    public boolean blocked;
    public String blockReason;

    public ScoreCard(String model, String ruleVersion, String market, String symbol) {
        this.model = model;
        this.ruleVersion = ruleVersion;
        this.market = market;
        this.symbol = symbol;
    }

    public ScoreItem add(ScoreItem it) { items.add(it); return it; }

    public double S() { return round1(items.stream().mapToDouble(ScoreItem::points).sum()); }

    public double W() { return round1(items.stream().filter(ScoreItem::evaluated).mapToDouble(i -> i.max).sum()); }

    public double total() { return round1(items.stream().mapToDouble(i -> i.max).sum()); }

    public double rangeHigh() { return round1(S() + total() - W()); }

    public double coverage() { double t = total(); return t == 0 ? 0 : round1(W() / t * 100); }

    public static final class Area {
        public double max, evaluatedMax, points;
        public int evaluatedItems, items;
        public boolean valid() { return evaluatedMax > 0 && points / evaluatedMax >= 0.5; }
    }

    public Map<String, Area> areas() {
        Map<String, Area> m = new LinkedHashMap<>();
        for (ScoreItem i : items) {
            Area a = m.computeIfAbsent(i.area, k -> new Area());
            a.max += i.max;
            a.items++;
            if (i.evaluated()) {
                a.evaluatedMax += i.max;
                a.points += i.points();
                a.evaluatedItems++;
            }
        }
        return m;
    }

    public int validAreas() { return (int) areas().values().stream().filter(Area::valid).count(); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model);
        m.put("ruleVersion", ruleVersion);
        m.put("market", market);
        m.put("symbol", symbol);
        m.put("name", name);
        m.put("asOf", asOf);
        m.put("S", S());
        m.put("W", W());
        m.put("coverage", coverage());
        m.put("rangeLow", S());
        m.put("rangeHigh", rangeHigh());
        m.put("display", String.format("확인 %.1f/%.0f점, 커버리지 %.0f%%, 전체 가능 범위 %.1f~%.1f",
                S(), W(), coverage(), S(), rangeHigh()));
        Map<String, Object> am = new LinkedHashMap<>();
        areas().forEach((k, a) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("max", a.max);
            x.put("evaluatedMax", round1(a.evaluatedMax));
            x.put("points", round1(a.points));
            x.put("valid", a.valid());
            x.put("evaluatedItems", a.evaluatedItems);
            x.put("items", a.items);
            am.put(k, x);
        });
        m.put("areas", am);
        m.put("validAreas", validAreas());
        m.put("status", status);
        m.put("statusLabel", statusLabel);
        m.put("statusReason", statusReason);
        m.put("gradeQualifier", gradeQualifier);
        m.put("confidence", confidence);
        m.put("filterPassed", filterPassed);
        m.put("filterUnconfirmed", filterUnconfirmed);
        m.put("mandatoryPassed", mandatoryPassed);
        m.put("blocked", blocked);
        m.put("blockReason", blockReason);
        List<Object> it = new ArrayList<>();
        for (ScoreItem i : items) it.add(i.toMap());
        m.put("items", it);
        m.put("filters", filters);
        m.put("mandatory", mandatory);
        m.put("pros", pros);
        m.put("cons", cons);
        m.put("missing", missing);
        m.put("nextChecks", nextChecks);
        m.put("indirect", indirect);
        m.put("info", info);
        return m;
    }

    public static double round1(double d) { return Math.round(d * 10.0) / 10.0; }
}
