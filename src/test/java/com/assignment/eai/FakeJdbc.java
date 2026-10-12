package com.assignment.eai;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 테스트 전용 인메모리 JDBC 드라이버 (jdbc:fake:{db}).
 * 이 프로젝트가 쓰는 단순 SQL(SELECT/INSERT/UPDATE + WHERE col = ?)만 해석하며
 * PK 중복, 트랜잭션(commit/rollback), 장애 주입(연결/커밋/특정 UPDATE 실패)을 흉내 낸다.
 */
public final class FakeJdbc implements Driver {

    /** db -> table -> rows */
    static final Map<String, Map<String, List<Map<String, String>>>> DB = new ConcurrentHashMap<>();
    static final Map<String, String[]> PK = Map.of(
            "ORDER_TB", new String[]{"ORDER_ID", "APPLICANT_KEY"},
            "SHIPMENT_TB", new String[]{"SHIPMENT_ID", "APPLICANT_KEY"});

    static volatile boolean failConnect;
    static volatile boolean failCommit;
    static final Set<String> failUpdateFor = ConcurrentHashMap.newKeySet();

    static {
        try {
            DriverManager.registerDriver(new FakeJdbc());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reset() {
        DB.clear();
        failConnect = false;
        failCommit = false;
        failUpdateFor.clear();
    }

    static List<Map<String, String>> table(String db, String table) {
        return DB.computeIfAbsent(db, k -> new ConcurrentHashMap<>()).computeIfAbsent(table, k -> new ArrayList<>());
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null;
        if (failConnect) throw new SQLNonTransientConnectionException("fake connect refused", "08001");
        return new Conn(url.substring("jdbc:fake:".length())).proxy();
    }

    @Override public boolean acceptsURL(String url) { return url != null && url.startsWith("jdbc:fake:"); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 1; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() { return Logger.getGlobal(); }

    // ------------------------------------------------------------------ connection

    private static final class Conn {
        final String db;
        boolean autoCommit = true;
        boolean closed;
        final List<Runnable> pending = new ArrayList<>();
        final List<Map<String, String>[]> pendingRows = new ArrayList<>();

        Conn(String db) {
            this.db = db;
        }

        Connection proxy() {
            InvocationHandler h = (p, m, a) -> {
                switch (m.getName()) {
                    case "setAutoCommit": autoCommit = (Boolean) a[0]; return null;
                    case "getAutoCommit": return autoCommit;
                    case "commit":
                        if (failCommit) throw new SQLException("fake commit failure", "40000");
                        synchronized (DB) { pending.forEach(Runnable::run); }
                        pending.clear();
                        pendingRows.clear();
                        return null;
                    case "rollback": pending.clear(); pendingRows.clear(); return null;
                    case "close": closed = true; pending.clear(); pendingRows.clear(); return null;
                    case "isClosed": return closed;
                    case "prepareStatement": return new Stmt(this, (String) a[0]).proxy();
                    case "toString": return "FakeConnection(" + db + ")";
                    case "hashCode": return System.identityHashCode(this);
                    case "equals": return p == a[0];
                    default: throw new UnsupportedOperationException(m.getName());
                }
            };
            return (Connection) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(), new Class[]{Connection.class}, h);
        }
    }

    // ------------------------------------------------------------------ statement

    private static final Pattern INSERT = Pattern.compile("INSERT INTO (\\w+) \\(([^)]+)\\) VALUES");
    private static final Pattern SELECT = Pattern.compile("SELECT (.+?) FROM (\\w+) WHERE (.+?)(?: ORDER BY (\\w+))?$");
    private static final Pattern UPDATE = Pattern.compile("UPDATE (\\w+) SET (\\w+) = '(\\w+)' WHERE (.+)$");

    private static final class Stmt {
        final Conn conn;
        final String sql;
        final Map<Integer, String> params = new LinkedHashMap<>();

        Stmt(Conn conn, String sql) {
            this.conn = conn;
            this.sql = sql.trim();
        }

        PreparedStatement proxy() {
            InvocationHandler h = (p, m, a) -> {
                switch (m.getName()) {
                    case "setString": params.put((Integer) a[0], (String) a[1]); return null;
                    case "setQueryTimeout": case "close": return null;
                    case "executeQuery": return query();
                    case "executeUpdate": return update();
                    case "toString": return sql;
                    case "hashCode": return System.identityHashCode(this);
                    case "equals": return p == a[0];
                    default: throw new UnsupportedOperationException(m.getName());
                }
            };
            return (PreparedStatement) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(), new Class[]{PreparedStatement.class}, h);
        }

        /** where 절: "A = ? AND TRIM(B) = 'N'" */
        boolean matches(Map<String, String> row, String where, int[] paramIdx) {
            for (String cond : where.split(" AND ")) {
                String[] kv = cond.split(" = ");
                String col = kv[0].trim();
                boolean trim = col.startsWith("TRIM(");
                if (trim) col = col.substring(5, col.length() - 1);
                String expected = kv[1].trim().equals("?") ? params.get(paramIdx[0]++) : kv[1].trim().replace("'", "");
                String actual = row.get(col);
                if (trim && actual != null) actual = actual.trim();
                if (actual == null || !actual.equals(expected)) return false;
            }
            return true;
        }

        ResultSet query() {
            Matcher m = SELECT.matcher(sql);
            if (!m.matches()) throw new UnsupportedOperationException(sql);
            List<String> cols = new ArrayList<>();
            for (String c : m.group(1).split(",")) cols.add(c.trim());
            List<Map<String, String>> out = new ArrayList<>();
            synchronized (DB) {
                for (Map<String, String> row : table(conn.db, m.group(2))) {
                    if (matches(row, m.group(3), new int[]{1})) out.add(row);
                }
            }
            if (m.group(4) != null) {
                String ob = m.group(4);
                out.sort((x, y) -> x.get(ob).compareTo(y.get(ob)));
            }
            return rs(cols, out);
        }

        int update() throws SQLException {
            Matcher ins = INSERT.matcher(sql);
            if (ins.find()) {
                String tbl = ins.group(1);
                Map<String, String> row = new LinkedHashMap<>();
                String[] cols = ins.group(2).split(",");
                for (int i = 0; i < cols.length; i++) row.put(cols[i].trim(), params.get(i + 1));
                synchronized (DB) {
                    List<Map<String, String>> t = table(conn.db, tbl);
                    if (exists(t, tbl, row) || pendingExists(tbl, row)) {
                        throw new SQLIntegrityConstraintViolationException("duplicate key " + row, "23000");
                    }
                }
                Runnable apply = () -> table(conn.db, tbl).add(row);
                if (conn.autoCommit) {
                    synchronized (DB) { apply.run(); }
                } else {
                    conn.pending.add(apply);
                    @SuppressWarnings("unchecked") Map<String, String>[] holder = new Map[]{row};
                    conn.pendingRows.add(holder);
                }
                return 1;
            }
            Matcher up = UPDATE.matcher(sql);
            if (up.matches()) {
                for (String id : failUpdateFor) {
                    if (params.containsValue(id)) throw new SQLException("fake update failure for " + id, "HY000");
                }
                int n = 0;
                synchronized (DB) {
                    for (Map<String, String> row : table(conn.db, up.group(1))) {
                        if (matches(row, up.group(4), new int[]{1})) {
                            row.put(up.group(2), up.group(3));
                            n++;
                        }
                    }
                }
                return n;
            }
            throw new UnsupportedOperationException(sql);
        }

        boolean exists(List<Map<String, String>> t, String tbl, Map<String, String> row) {
            String[] pk = PK.get(tbl);
            for (Map<String, String> r : t) {
                boolean same = true;
                for (String k : pk) same &= r.get(k).equals(row.get(k));
                if (same) return true;
            }
            return false;
        }

        boolean pendingExists(String tbl, Map<String, String> row) {
            List<Map<String, String>> list = new ArrayList<>();
            for (Map<String, String>[] h : conn.pendingRows) list.add(h[0]);
            return !conn.pending.isEmpty() && exists(list, tbl, row);
        }
    }

    private static ResultSet rs(List<String> cols, List<Map<String, String>> rows) {
        int[] cursor = {-1};
        Set<String> colSet = new HashSet<>(cols);
        InvocationHandler h = (p, m, a) -> {
            switch (m.getName()) {
                case "next": return ++cursor[0] < rows.size();
                case "getString":
                    String col = a[0] instanceof Integer ? cols.get((Integer) a[0] - 1) : (String) a[0];
                    if (!colSet.contains(col)) throw new SQLException("no column " + col);
                    return rows.get(cursor[0]).get(col);
                case "close": return null;
                default: throw new UnsupportedOperationException(m.getName());
            }
        };
        return (ResultSet) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(), new Class[]{ResultSet.class}, h);
    }
}
