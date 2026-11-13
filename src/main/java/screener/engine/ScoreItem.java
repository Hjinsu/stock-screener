package screener.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 세부 점수 항목. grade: 1(충족) / 0.5(부분) / 0(미충족·반대) / null(미평가 — 0점으로 채우지 않음).
 */
public final class ScoreItem {
    public final String area;
    public final String key;
    public final String name;
    public final double max;
    public Double grade;
    public String observed;
    public String criterion;
    public String source;
    public String asOf;
    public boolean delayed;
    public boolean substitute;  // 대체 지표/근사 산출
    public String note;

    public ScoreItem(String area, String key, String name, double max, String criterion) {
        this.area = area;
        this.key = key;
        this.name = name;
        this.max = max;
        this.criterion = criterion;
    }

    public boolean evaluated() { return grade != null; }

    public double points() { return grade == null ? 0 : grade * max; }

    public ScoreItem grade(Double g, String observed) {
        this.grade = g;
        this.observed = observed;
        return this;
    }

    public ScoreItem unevaluated(String why) {
        this.grade = null;
        this.note = why;
        return this;
    }

    public ScoreItem src(String source, String asOf) {
        this.source = source;
        this.asOf = asOf;
        return this;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("area", area);
        m.put("key", key);
        m.put("name", name);
        m.put("max", max);
        m.put("grade", grade);
        m.put("points", grade == null ? null : points());
        m.put("observed", observed);
        m.put("criterion", criterion);
        m.put("source", source);
        m.put("asOf", asOf);
        m.put("delayed", delayed);
        m.put("substitute", substitute);
        m.put("note", note);
        return m;
    }
}
