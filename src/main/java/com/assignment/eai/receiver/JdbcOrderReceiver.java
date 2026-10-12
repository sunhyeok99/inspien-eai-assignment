package com.assignment.eai.receiver;

import com.assignment.eai.model.OrderRecord;
import com.assignment.eai.model.PendingOrder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 쇼핑몰 주문 테이블(ORDER_TB) JDBC 어댑터.
 * 트랜잭션 경계(commit/rollback)는 호출하는 Flow 가 관리한다.
 * 공유 테이블이므로 모든 SQL 은 APPLICANT_KEY 로 범위를 한정한다.
 */
public final class JdbcOrderReceiver {

    private final String table;

    public JdbcOrderReceiver(String table) {
        this.table = table;
    }

    /** 채번용: 내 지원자 키로 사용 중인 ORDER_ID 전체 */
    public List<String> findOrderIds(Connection c, String applicantKey) {
        String sql = "SELECT ORDER_ID FROM " + table + " WHERE APPLICANT_KEY = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(JdbcSupport.QUERY_TIMEOUT_SECONDS);
            ps.setString(1, applicantKey);
            List<String> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw JdbcSupport.classify(e, "ORDER_ID 조회");
        }
    }

    public int insert(Connection c, List<OrderRecord> records) {
        String sql = "INSERT INTO " + table
                + " (ORDER_ID, APPLICANT_KEY, USER_ID, ITEM_ID, NAME, ADDRESS, ITEM_NAME, PRICE, STATUS)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        int count = 0;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(JdbcSupport.QUERY_TIMEOUT_SECONDS);
            for (OrderRecord r : records) {
                ps.setString(1, r.orderId());
                ps.setString(2, r.applicantKey());
                ps.setString(3, r.userId());
                ps.setString(4, r.itemId());
                ps.setString(5, r.name());
                ps.setString(6, r.address());
                ps.setString(7, r.itemName());
                ps.setString(8, r.price());
                ps.setString(9, r.status());
                count += ps.executeUpdate();
            }
            return count;
        } catch (SQLException e) {
            throw JdbcSupport.classify(e, "ORDER_TB INSERT");
        }
    }

    /** 배치 대상: 내 지원자 키 + 미전송(STATUS='N') */
    public List<PendingOrder> selectPending(Connection c, String applicantKey) {
        String sql = "SELECT ORDER_ID, ITEM_ID, ADDRESS FROM " + table
                + " WHERE APPLICANT_KEY = ? AND TRIM(STATUS) = 'N' ORDER BY ORDER_ID";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(JdbcSupport.QUERY_TIMEOUT_SECONDS);
            ps.setString(1, applicantKey);
            List<PendingOrder> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PendingOrder(rs.getString("ORDER_ID"), rs.getString("ITEM_ID"), rs.getString("ADDRESS")));
                }
            }
            return list;
        } catch (SQLException e) {
            throw JdbcSupport.classify(e, "미전송 주문 조회");
        }
    }

    /** 운송사 적재 성공 건 상태 갱신. 이미 Y 인 건은 건드리지 않는다(재실행 안전) */
    public int markSent(Connection c, String applicantKey, String orderId) {
        String sql = "UPDATE " + table + " SET STATUS = 'Y' WHERE APPLICANT_KEY = ? AND ORDER_ID = ? AND TRIM(STATUS) = 'N'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(JdbcSupport.QUERY_TIMEOUT_SECONDS);
            ps.setString(1, applicantKey);
            ps.setString(2, orderId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.classify(e, "ORDER_TB STATUS UPDATE(" + orderId + ")");
        }
    }
}
