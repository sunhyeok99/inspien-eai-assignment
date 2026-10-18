package com.assignment.eai.receiver;

import com.assignment.eai.model.ShipmentRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 운송사 테이블(SHIPMENT_TB) JDBC 어댑터 */
public final class JdbcShipmentReceiver {

    private final String table;
    private final int queryTimeoutSeconds;

    public JdbcShipmentReceiver(String table) {
        this(table, JdbcSupport.DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    public JdbcShipmentReceiver(String table, int queryTimeoutSeconds) {
        this.table = table;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public List<String> findShipmentIds(Connection c, String applicantKey) {
        String sql = "SELECT SHIPMENT_ID FROM " + table + " WHERE APPLICANT_KEY = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, applicantKey);
            List<String> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw JdbcSupport.classify(e, "SHIPMENT_ID 조회");
        }
    }

    /**
     * 멱등성 확인: 이미 운송사에 적재된 주문인지.
     * (이전 배치에서 SHIPMENT INSERT 는 성공했지만 ORDER STATUS UPDATE 가 실패한 경우 중복 적재 방지)
     */
    public Optional<String> findShipmentIdByOrder(Connection c, String applicantKey, String orderId) {
        String sql = "SELECT SHIPMENT_ID FROM " + table + " WHERE APPLICANT_KEY = ? AND ORDER_ID = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, applicantKey);
            ps.setString(2, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw JdbcSupport.classify(e, "운송 적재 여부 조회(" + orderId + ")");
        }
    }

    public void insert(Connection c, ShipmentRecord r) {
        String sql = "INSERT INTO " + table + " (SHIPMENT_ID, APPLICANT_KEY, ORDER_ID, ITEM_ID, ADDRESS) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, r.shipmentId());
            ps.setString(2, r.applicantKey());
            ps.setString(3, r.orderId());
            ps.setString(4, r.itemId());
            ps.setString(5, r.address());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw JdbcSupport.classify(e, "SHIPMENT_TB INSERT(" + r.orderId() + ")");
        }
    }
}
