package screener.model;

import java.util.ArrayList;
import java.util.List;

/** 최근 공시 스캔 결과. ok=false면 스캔 자체가 실패(→ 필수 검증 미확인). */
public final class DisclosureCheck {
    public enum Level { BLOCK, REVIEW, INFO }

    public record Event(String date, String title, Level level, String rule, String url) { }

    public boolean ok;
    public String source;
    public String window;     // 스캔 기간
    public String error;
    public int scanned;
    public final List<Event> events = new ArrayList<>();
    public String indirectNote; // 미국 13F/Form 4/ATS 등 간접 자료 메모

    public boolean hasBlock() { return events.stream().anyMatch(e -> e.level == Level.BLOCK); }
    public boolean hasReview() { return events.stream().anyMatch(e -> e.level == Level.REVIEW); }

    public static DisclosureCheck failed(String source, String err) {
        DisclosureCheck d = new DisclosureCheck();
        d.ok = false;
        d.source = source;
        d.error = err;
        return d;
    }
}
