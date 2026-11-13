package screener.model;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 실행 단위 소스별 호출/실패 기록. 한 소스 실패가 다른 소스에 전파되지 않도록 격리하는 데 사용한다. */
public final class SourceStatus {
    public final String name;
    public int calls, cacheHits, failures, items;
    public String lastError;
    public String lastOkAt;
    public String note;
    public boolean disabled;

    public SourceStatus(String name) { this.name = name; }

    public synchronized void ok(boolean cached) {
        if (cached) cacheHits++; else calls++;
        lastOkAt = LocalDateTime.now().withNano(0).toString();
    }

    public synchronized void fail(String err) {
        calls++;
        failures++;
        lastError = err;
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("calls", calls);
        m.put("cacheHits", cacheHits);
        m.put("failures", failures);
        m.put("items", items);
        m.put("lastError", lastError);
        m.put("lastOkAt", lastOkAt);
        m.put("note", note);
        m.put("disabled", disabled);
        return m;
    }

    /** 실행 단위 레지스트리. */
    public static final class Registry {
        private final Map<String, SourceStatus> map = new ConcurrentHashMap<>();

        public SourceStatus get(String name) { return map.computeIfAbsent(name, SourceStatus::new); }

        public Map<String, SourceStatus> all() { return new java.util.TreeMap<>(map); }
    }
}
