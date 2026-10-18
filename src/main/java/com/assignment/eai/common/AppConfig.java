package com.assignment.eai.common;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * 실행 설정. config/application.properties + config/conn/*.json (init 명령이 생성, Git 제외).
 */
public final class AppConfig {

    public static final Path CONFIG_DIR = Path.of("config");
    public static final Path PROPERTIES = CONFIG_DIR.resolve("application.properties");
    public static final Path CONN_DIR = CONFIG_DIR.resolve("conn");
    public static final String ORDER_DB_FILE = "order_db.json";
    public static final String SHIPMENT_DB_FILE = "shipment_db.json";
    public static final String FTP_FILE = "ftp.json";

    private final Properties props;
    private final ConnectionInfo orderDb;
    private final ConnectionInfo shipmentDb;
    private final ConnectionInfo ftp;

    public AppConfig(Properties props, ConnectionInfo orderDb, ConnectionInfo shipmentDb, ConnectionInfo ftp) {
        this.props = props;
        this.orderDb = orderDb;
        this.shipmentDb = shipmentDb;
        this.ftp = ftp;
    }

    public static AppConfig load() {
        if (!Files.exists(PROPERTIES)) {
            throw new EaiException(ErrorCode.CONFIG, PROPERTIES + " 가 없습니다. 먼저 init 명령으로 정보를 수신하세요.");
        }
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(PROPERTIES, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            throw new EaiException(ErrorCode.CONFIG, "설정 파일 읽기 실패: " + e.getMessage(), e);
        }
        return new AppConfig(props,
                new ConnectionInfo("order.db", readConn(ORDER_DB_FILE), props, "order.db"),
                new ConnectionInfo("shipment.db", readConn(SHIPMENT_DB_FILE), props, "shipment.db"),
                new ConnectionInfo("ftp", readConn(FTP_FILE), props, "ftp"));
    }

    private static Map<String, Object> readConn(String file) {
        Path p = CONN_DIR.resolve(file);
        if (!Files.exists(p)) {
            return null;
        }
        try {
            return Json.parseObject(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            throw new EaiException(ErrorCode.CONFIG, p + " 읽기 실패: " + e.getMessage(), e);
        }
    }

    public String get(String key, String def) {
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public String require(String key) {
        String v = get(key, null);
        if (v == null) {
            throw new EaiException(ErrorCode.CONFIG, "필수 설정 누락: " + key);
        }
        return v;
    }

    public int getInt(String key, int def) {
        return Integer.parseInt(get(key, String.valueOf(def)));
    }

    public boolean getBool(String key, boolean def) {
        return Boolean.parseBoolean(get(key, String.valueOf(def)));
    }

    // -------- 업무 설정

    public String applicantKey() { return require("applicant.key"); }
    public String applicantName() { return require("applicant.name"); }

    public int serverPort() { return getInt("server.port", 8080); }
    public int serverThreads() { return getInt("server.threads", 4); }
    public int serverMaxBodyBytes() { return getInt("server.max-body-kb", 5 * 1024) * 1024; }
    public int batchIntervalSeconds() { return getInt("batch.interval.seconds", 300); }
    public int batchInitialDelaySeconds() { return getInt("batch.initial-delay.seconds", 10); }

    public Charset receiptCharset() { return Charset.forName(get("receipt.charset", "UTF-8")); }
    public String orderTable() { return get("order.table", "ORDER_TB"); }
    public String shipmentTable() { return get("shipment.table", "SHIPMENT_TB"); }

    public int retryMaxAttempts() { return getInt("retry.max-attempts", 3); }
    public long retryBackoffMs() { return getInt("retry.backoff-ms", 1000); }
    public int connectTimeoutSeconds() { return getInt("connect.timeout.seconds", 10); }
    public int queryTimeoutSeconds() { return getInt("jdbc.query-timeout.seconds", 30); }
    public int duplicateRetry() { return getInt("order.duplicate-retry", 3); }

    public boolean ftpUseControlHostForPassive() { return getBool("ftp.passive.use-control-host", true); }

    public Path logDir() { return Path.of(get("log.dir", "logs")); }

    public ConnectionInfo orderDb() { return orderDb; }
    public ConnectionInfo shipmentDb() { return shipmentDb; }
    public ConnectionInfo ftp() { return ftp; }
}
