package screener.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/** 파일+콘솔 로거. 모든 출력은 비밀값 마스킹을 거친다. */
public final class Log {
    private static final List<String> SECRETS = new CopyOnWriteArrayList<>();
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)(crtfc_key|token|apikey|api_key|serviceKey|AUTH_KEY)=([^&\\s\"']+)");
    private static volatile Path file;
    private static volatile boolean quiet;

    private Log() { }

    public static void init(Path logDir) {
        try {
            Files.createDirectories(logDir);
            file = logDir.resolve("screener-" + java.time.LocalDate.now() + ".log");
        } catch (IOException e) {
            System.err.println("로그 디렉터리 생성 실패: " + e.getMessage());
        }
    }

    public static void setQuiet(boolean q) { quiet = q; }

    /** 마스킹할 비밀값 등록(4자 이상만). */
    public static void registerSecret(String v) {
        if (v != null && v.length() >= 4 && !SECRETS.contains(v)) SECRETS.add(v);
    }

    public static String redact(String s) {
        if (s == null) return null;
        String r = s;
        for (String sec : SECRETS) r = r.replace(sec, "***");
        return QUERY_SECRET.matcher(r).replaceAll("$1=***");
    }

    public static void info(String msg) { write("INFO", msg); }
    public static void warn(String msg) { write("WARN", msg); }
    public static void error(String msg) { write("ERROR", msg); }

    private static synchronized void write(String level, String msg) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + " [" + level + "] " + redact(msg);
        if (!quiet || !"INFO".equals(level)) {
            (("ERROR".equals(level)) ? System.err : System.out).println(line);
        }
        if (file != null) {
            try {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        }
    }
}
