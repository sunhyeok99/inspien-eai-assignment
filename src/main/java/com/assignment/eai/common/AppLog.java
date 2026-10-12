package com.assignment.eai.common;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 애플리케이션(디버깅용) 로그 설정. JDK java.util.logging 사용.
 * 콘솔 + logs/app.log 에 "시간 레벨 [txId] 클래스 - 메시지" 형식으로 남긴다.
 * (운영자용 처리 이력은 {@link MonitorLog} 가 별도 파일로 남긴다)
 */
public final class AppLog {

    private static volatile boolean initialized;

    private AppLog() {
    }

    public static synchronized void init(Path logDir) {
        if (initialized) {
            return;
        }
        try {
            Files.createDirectories(logDir);
            LogManager.getLogManager().reset();
            Logger root = Logger.getLogger("");
            root.setLevel(Level.INFO);

            Formatter formatter = new LineFormatter();
            Handler console = new ConsoleHandler();
            console.setFormatter(formatter);
            console.setEncoding(StandardCharsets.UTF_8.name());
            root.addHandler(console);

            FileHandler file = new FileHandler(logDir.resolve("app.%g.log").toString(), 10 * 1024 * 1024, 5, true);
            file.setEncoding(StandardCharsets.UTF_8.name());
            file.setFormatter(formatter);
            root.addHandler(file);
            initialized = true;
        } catch (IOException e) {
            throw new IllegalStateException("로그 파일 초기화 실패: " + e.getMessage(), e);
        }
    }

    public static Logger get(Class<?> type) {
        return Logger.getLogger(type.getName());
    }

    private static final class LineFormatter extends Formatter {
        private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder();
            sb.append(LocalDateTime.now().format(TS)).append(' ')
                    .append(String.format("%-7s", r.getLevel().getName())).append(' ')
                    .append('[').append(TxContext.current()).append("] ")
                    .append(shortName(r.getLoggerName())).append(" - ")
                    .append(formatMessage(r)).append(System.lineSeparator());
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }

        private static String shortName(String name) {
            if (name == null) return "";
            int i = name.lastIndexOf('.');
            return i < 0 ? name : name.substring(i + 1);
        }
    }
}
