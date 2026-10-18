package com.assignment.eai.common;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * 복호화된 접속 정보(DB/FTP).
 * <p>
 * 정보 수신 API 가 돌려주는 접속 정보의 키 이름(HOST/host/IP, USER/USERNAME/ID ...)을 미리 알 수 없기 때문에
 * 대소문자·'_' 를 무시하고 여러 별칭으로 찾는다. application.properties 의 {prefix}.url / .user / .password ...
 * 값이 있으면 그것이 우선한다(키 이름이 예상과 달라도 설정으로 보정 가능).
 */
public final class ConnectionInfo {

    private final String name;
    private final Map<String, Object> values = new LinkedHashMap<>();

    public ConnectionInfo(String name, Map<String, Object> raw, Properties overrides, String prefix) {
        this.name = name;
        if (raw != null) {
            raw.forEach((k, v) -> values.put(normalize(k), v));
        }
        if (overrides != null) {
            for (String key : overrides.stringPropertyNames()) {
                if (key.startsWith(prefix + ".")) {
                    String v = overrides.getProperty(key).trim();
                    if (!v.isEmpty()) {
                        values.put(normalize(key.substring(prefix.length() + 1)), v);
                    }
                }
            }
        }
    }

    private static String normalize(String key) {
        return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private String find(String... aliases) {
        for (String alias : aliases) {
            Object v = values.get(normalize(alias));
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v).trim();
            }
        }
        return null;
    }

    public String host() {
        String host = find("host", "hostname", "ip", "server", "address", "addr");
        return host != null ? host : hostFromUrl();
    }

    /** FTP 처럼 호스트를 URL 키로 주는 경우: "211.0.0.1", "ftp://host:21/path" 에서 host 만 꺼낸다 (JDBC URL 은 제외). */
    private String hostFromUrl() {
        String url = find("url");
        if (url == null || url.startsWith("jdbc:")) {
            return null;
        }
        String s = url.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
        s = s.substring(s.indexOf('@') + 1);
        int end = s.length();
        for (char c : new char[] {'/', ':'}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        return s.substring(0, end).isBlank() ? null : s.substring(0, end);
    }
    public String user() { return find("user", "username", "userid", "id", "login", "account"); }
    public String password() { return find("password", "pw", "pwd", "pass", "passwd"); }
    public String database() { return find("database", "databasename", "db", "dbname", "sid", "servicename", "service", "schema"); }
    public String type() { return find("type", "dbtype", "dbms", "vendor", "driver", "protocol"); }
    public String remoteDir() { return find("remotedir", "remotepath", "path", "dir", "directory", "folder"); }

    public int port(int defaultPort) {
        String p = find("port");
        if (p == null) return defaultPort;
        try {
            return Integer.parseInt(p.replace(".0", ""));
        } catch (NumberFormatException e) {
            throw new EaiException(ErrorCode.CONFIG, name + " port 형식 오류: " + p);
        }
    }

    /** JDBC URL. url 값이 있으면 그대로, 없으면 type/host/port/database 로 조립한다. */
    public String jdbcUrl() {
        String url = find("url", "jdbcurl", "jdbc", "connectionurl");
        if (url != null) {
            return url.startsWith("jdbc:") ? url : "jdbc:" + url;
        }
        String host = require(host(), "host");
        String db = database();
        String type = type() == null ? guessTypeByPort() : type().toLowerCase(Locale.ROOT);
        if (type.contains("oracle")) {
            return "jdbc:oracle:thin:@//" + host + ":" + port(1521) + "/" + require(db, "database(service name)");
        } else if (type.contains("mariadb")) {
            return "jdbc:mariadb://" + host + ":" + port(3306) + "/" + nullToEmpty(db);
        } else if (type.contains("mysql")) {
            return "jdbc:mysql://" + host + ":" + port(3306) + "/" + nullToEmpty(db)
                    + "?characterEncoding=UTF-8&serverTimezone=Asia/Seoul";
        } else if (type.contains("postgre")) {
            return "jdbc:postgresql://" + host + ":" + port(5432) + "/" + nullToEmpty(db);
        } else if (type.contains("sqlserver") || type.contains("mssql")) {
            return "jdbc:sqlserver://" + host + ":" + port(1433) + ";databaseName=" + nullToEmpty(db)
                    + ";encrypt=false;trustServerCertificate=true";
        } else if (type.contains("hana") || type.contains("sap")) {
            return "jdbc:sap://" + host + ":" + port(30015) + "/" + (db == null ? "" : "?databaseName=" + db);
        }
        throw new EaiException(ErrorCode.CONFIG, name + " DB 종류를 알 수 없습니다(type=" + type
                + "). application.properties 에 " + name + " url 을 직접 지정하세요.");
    }

    private String guessTypeByPort() {
        int port = port(-1);
        switch (port) {
            case 1521: return "oracle";
            case 3306: return "mysql";
            case 5432: return "postgresql";
            case 1433: return "sqlserver";
            default:
                if (port >= 30000 && port < 40000) return "hana";
                return "unknown";
        }
    }

    private String require(String v, String field) {
        if (v == null) {
            throw new EaiException(ErrorCode.CONFIG, name + " 접속 정보에 " + field + " 값이 없습니다. 키 목록=" + values.keySet());
        }
        return v;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public String name() {
        return name;
    }

    /** 로그용: 비밀번호를 가린 요약 */
    public String masked() {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.replaceAll((k, v) -> k.contains("pw") || k.contains("pass") ? "****" : v);
        return name + copy;
    }
}
