package com.assignment.eai.receiver;

import com.assignment.eai.common.AppLog;
import com.assignment.eai.common.ConnectionInfo;
import com.assignment.eai.common.EaiException;
import com.assignment.eai.common.ErrorCode;
import com.assignment.eai.common.Retry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.logging.Logger;

/** JDBC 연결 생성(재시도 포함)과 SQLException → ErrorCode 분류 */
public final class JdbcSupport {

    private static final Logger log = AppLog.get(JdbcSupport.class);
    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    private final int maxAttempts;
    private final long backoffMs;
    private final int connectTimeoutSeconds;

    public JdbcSupport(int maxAttempts, long backoffMs, int connectTimeoutSeconds) {
        this.maxAttempts = maxAttempts;
        this.backoffMs = backoffMs;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public Connection open(ConnectionInfo info) {
        String url = info.jdbcUrl();
        return Retry.call(info.name() + " 연결", maxAttempts, backoffMs, () -> {
            try {
                DriverManager.setLoginTimeout(connectTimeoutSeconds);
                Connection c = DriverManager.getConnection(url, info.user(), info.password());
                log.fine(info.name() + " 연결 성공");
                return c;
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().contains("No suitable driver")) {
                    throw new EaiException(ErrorCode.CONFIG, info.name() + " JDBC 드라이버가 없습니다: " + url, e);
                }
                throw new EaiException(ErrorCode.JDBC_CONNECT, info.name() + " 연결 실패: " + e.getMessage(), e);
            }
        });
    }

    /** SQLState 표준 분류: 08xxx 연결 오류, 23xxx 무결성(PK 중복 등) */
    public static EaiException classify(SQLException e, String context) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (e instanceof SQLIntegrityConstraintViolationException || state.startsWith("23")) {
            return new EaiException(ErrorCode.JDBC_DUPLICATE, context + " 무결성 위반(PK 중복 등): " + e.getMessage(), e);
        }
        if (e instanceof SQLTransientConnectionException || e instanceof SQLNonTransientConnectionException
                || state.startsWith("08")) {
            return new EaiException(ErrorCode.JDBC_CONNECT, context + " DB 연결 끊김: " + e.getMessage(), e);
        }
        return new EaiException(ErrorCode.JDBC_SQL, context + " SQL 오류[" + state + "]: " + e.getMessage(), e);
    }

    public static void rollbackQuietly(Connection c) {
        if (c == null) return;
        try {
            c.rollback();
        } catch (SQLException e) {
            log.warning("rollback 실패: " + e.getMessage());
        }
    }

    public static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception e) {
            log.fine("close 실패: " + e.getMessage());
        }
    }
}
