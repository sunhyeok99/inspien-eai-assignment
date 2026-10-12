package com.assignment.eai.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 운영 관점 모니터링 로그 (처리 이력).
 * <p>
 * 요청(메시지) 단위로 "언제 / 어떤 인터페이스가 / 어느 단계에서 / 성공·실패했는지 / 몇 건 / 얼마나 걸렸는지"를
 * logs/monitor/monitor-yyyyMMdd.log 에 한 줄 JSON(JSON Lines)으로 저장한다.
 * 한 줄 JSON 이라 grep 으로 txId·FAIL 검색이 쉽고, 추후 모니터링 솔루션에 그대로 수집시킬 수 있다.
 * <p>
 * 실패한 원본 메시지는 logs/error-payload/{txId}.xml 로 보관해 원인 분석과 재처리에 사용한다.
 */
public final class MonitorLog {

    public enum Status { START, SUCCESS, FAIL, PARTIAL, SKIP, COMPENSATED }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Object LOCK = new Object();

    private static Path monitorDir = Path.of("logs", "monitor");
    private static Path payloadDir = Path.of("logs", "error-payload");

    private MonitorLog() {
    }

    public static void init(Path logDir) {
        monitorDir = logDir.resolve("monitor");
        payloadDir = logDir.resolve("error-payload");
    }

    /**
     * @param interfaceId IF_ORDER_001 / IF_SHIP_001
     * @param step        RECEIVE, VALIDATE, MAPPING, JDBC_ORDER, FTP_RECEIPT, BATCH_SELECT ... , END
     */
    public static void write(String interfaceId, String step, Status status, Integer count, Long elapsedMs,
                             String message, ErrorCode errorCode) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("time", LocalDateTime.now().format(TS));
        line.put("txId", TxContext.current());
        line.put("interfaceId", interfaceId);
        line.put("step", step);
        line.put("status", status.name());
        if (count != null) line.put("count", count);
        if (elapsedMs != null) line.put("elapsedMs", elapsedMs);
        if (errorCode != null) line.put("errorCode", errorCode.code());
        if (message != null) line.put("message", message);
        append(monitorDir.resolve("monitor-" + LocalDate.now().format(DAY) + ".log"), Json.write(line) + "\n");
    }

    public static void step(String interfaceId, String step, Status status, Integer count, String message) {
        write(interfaceId, step, status, count, null, message, null);
    }

    public static void fail(String interfaceId, String step, EaiException e, Long elapsedMs) {
        write(interfaceId, step, Status.FAIL, null, elapsedMs, e.getMessage(), e.errorCode());
    }

    /** 실패 메시지 원본 보관 (재처리용). 보관 경로를 반환 */
    public static Path savePayload(byte[] payload, String extension) {
        try {
            Files.createDirectories(payloadDir);
            Path file = payloadDir.resolve(TxContext.current() + "." + extension);
            Files.write(file, payload);
            return file;
        } catch (IOException e) {
            AppLog.get(MonitorLog.class).warning("실패 메시지 보관 실패: " + e.getMessage());
            return null;
        }
    }

    private static void append(Path file, String text) {
        synchronized (LOCK) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, text, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                // 모니터링 로그 실패가 업무 처리를 막으면 안 되므로 앱 로그에만 남긴다.
                AppLog.get(MonitorLog.class).warning("모니터링 로그 기록 실패: " + e.getMessage());
            }
        }
    }
}
