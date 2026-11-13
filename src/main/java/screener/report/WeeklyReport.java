package screener.report;

import screener.engine.Rules;
import screener.store.Db;
import screener.util.Json;
import screener.util.Num;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 주간 보고서(원본 02 §11 주간 중 요청 범위): 이번 주 한·미 심리 변화, 극단 공포·탐욕 발생, 신규·기존 후보 변화, 모의 보유 종목 위험.
 * 주(월~금)는 weekEnd(금요일) 기준. DB에 저장된 결과만 사용한다.
 */
public final class WeeklyReport {
    private WeeklyReport() { }

    public record Result(String periodKey, String text, String summary) { }

    public static Result build(Db db, LocalDate anyDayInWeek, String checkedAt) throws Exception {
        LocalDate mon = anyDayInWeek.with(DayOfWeek.MONDAY), fri = mon.plusDays(4);
        String a = mon.toString(), b = fri.toString();
        List<String> t = new ArrayList<>();
        Map<String, Object> c = new LinkedHashMap<>();
        t.add("■ 주간 보고서 " + a + " ~ " + b + " · 확인 " + checkedAt + " KST");
        StringBuilder s = new StringBuilder("[모의매매·주간] " + a.substring(5) + "~" + b.substring(5) + "\n");
        for (String m : new String[]{"KR", "US"}) {
            List<Map<String, Object>> rows = db.query("SELECT as_of, score FROM msi WHERE market=? AND as_of>=? AND as_of<=? AND score IS NOT NULL ORDER BY as_of", m, a, b);
            String line;
            if (rows.isEmpty()) line = "MSI 기록 없음(미평가)";
            else {
                List<Double> v = rows.stream().map(r -> ((Number) r.get("score")).doubleValue()).collect(Collectors.toList());
                double first = v.get(0), last = v.get(v.size() - 1);
                long fear = v.stream().filter(x -> Math.round(x) <= 15).count(), greed = v.stream().filter(x -> Math.round(x) >= 76).count(), euph = v.stream().filter(x -> Math.round(x) >= 91).count();
                line = String.format("MSI %.1f→%.1f(%+.1f, 최저 %.1f·최고 %.1f, %d일) · 극단적 공포 %s · 탐욕 이상 %s%s", first, last, last - first,
                        v.stream().mapToDouble(Double::doubleValue).min().getAsDouble(), v.stream().mapToDouble(Double::doubleValue).max().getAsDouble(), v.size(),
                        fear > 0 ? fear + "일 발생" : "없음", greed > 0 ? greed + "일" : "없음", euph > 0 ? "(극단적 환희 " + euph + "일)" : "");
                s.append(m).append(String.format(" MSI %.0f→%.0f%s%s\n", first, last, fear > 0 ? " 극단공포" + fear + "일" : "", greed > 0 ? " 탐욕" + greed + "일" : ""));
            }
            if (rows.isEmpty()) s.append(m).append(" MSI 미평가\n");
            c.put(m + "_msi", line);
            t.add("1. " + m + " 심리: " + line);
        }
        // 후보 변화
        List<Map<String, Object>> tr = db.query("SELECT market, model, name, from_state, to_state FROM transitions WHERE as_of>=? AND as_of<=? ORDER BY market, model", a, b);
        List<String> fresh = new ArrayList<>(), changed = new ArrayList<>();
        Map<String, Integer> byState = new LinkedHashMap<>();
        for (Map<String, Object> r : tr) {
            String from = (String) r.get("from_state"), to = (String) r.get("to_state");
            boolean cand = List.of("EARLY", "STRONG", "HIGH", "VERY_HIGH", "WATCH", "INTEREST", "PRIORITY", "OBSERVE").contains(to);
            boolean wasNone = from == null || List.of("NONE", "HOLD", "ENDED", "PENDING").contains(from);
            String lbl = r.get("market") + " " + r.get("model") + " " + Rules.label(to);
            if (cand && wasNone) fresh.add(r.get("market") + "·" + r.get("model") + " " + r.get("name") + "(" + Rules.label(to) + ")");
            else if (!wasNone) { changed.add(r.get("name") + " " + Rules.label(from) + "→" + Rules.label(to)); byState.merge(lbl, 1, Integer::sum); }
        }
        c.put("newCandidates", fresh);
        c.put("changedCandidates", changed);
        t.add("2. 신규 후보 " + fresh.size() + "건: " + (fresh.isEmpty() ? "없음" : String.join(", ", fresh.subList(0, Math.min(15, fresh.size())))));
        t.add("3. 기존 후보 변화 " + changed.size() + "건: " + (changed.isEmpty() ? "없음" : String.join(", ", changed.subList(0, Math.min(15, changed.size())))));
        s.append("신규 후보 ").append(fresh.size()).append(" · 기존 변화 ").append(changed.size()).append("\n");
        // 모의 보유 위험(최신 일일 보고서 기준)
        List<String> risk = new ArrayList<>();
        int severe = 0;
        for (String m : new String[]{"KR", "US"}) {
            List<Map<String, Object>> r = db.query("SELECT content_json FROM reports WHERE market=? AND report_type='DAILY' AND period_key<=? ORDER BY period_key DESC LIMIT 1", m, b);
            if (r.isEmpty()) { risk.add(m + " 일일 보고 없음(미평가)"); continue; }
            Map<String, Object> cj = Json.obj(Json.parse((String) r.get(0).get("content_json")));
            List<Object> hr = Json.arr(cj.get("holdingRisks"));
            if (hr == null || hr.isEmpty()) { risk.add(m + " 보유 없음"); continue; }
            for (Object x : hr) {
                Map<String, Object> h = Json.obj(x);
                if ("SEVERE".equals(h.get("level"))) severe++;
                risk.add(m + " " + h.get("name") + " [" + h.get("level") + "] " + h.get("reason") + " (" + Num.pct(h.get("ret") instanceof Number ? ((Number) h.get("ret")).doubleValue() : null) + ")");
            }
        }
        c.put("holdingRisks", risk);
        t.add("4. 모의 보유 종목 위험: " + String.join(" / ", risk));
        s.append("보유 위험: 중대 ").append(severe).append("건\n확인 ").append(checkedAt.substring(11, 16));
        t.add("※ 분석·모의 검증 전용");
        String text = String.join("\n", t);
        try (var ps = db.prepare("INSERT OR REPLACE INTO reports(market, report_type, period_key, checked_at, text, content_json, created_at) VALUES('ALL', 'WEEKLY', ?, ?, ?, ?, ?)")) {
            ps.setString(1, b); ps.setString(2, checkedAt); ps.setString(3, text); ps.setString(4, Json.write(c)); ps.setString(5, java.time.LocalDateTime.now().withNano(0).toString());
            ps.executeUpdate();
        }
        return new Result(b, text, KakaoReporter.fit(s.toString()));
    }
}
