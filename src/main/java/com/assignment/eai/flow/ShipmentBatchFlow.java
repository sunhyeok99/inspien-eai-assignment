package com.assignment.eai.flow;

import com.assignment.eai.common.AppLog;
import com.assignment.eai.common.ConnectionInfo;
import com.assignment.eai.common.EaiException;
import com.assignment.eai.common.ErrorCode;
import com.assignment.eai.common.IdGenerator;
import com.assignment.eai.common.MonitorLog;
import com.assignment.eai.common.MonitorLog.Status;
import com.assignment.eai.common.TxContext;
import com.assignment.eai.mapper.ShipmentMapper;
import com.assignment.eai.model.PendingOrder;
import com.assignment.eai.model.ShipmentRecord;
import com.assignment.eai.receiver.JdbcOrderReceiver;
import com.assignment.eai.receiver.JdbcShipmentReceiver;
import com.assignment.eai.receiver.JdbcSupport;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * 시나리오 2 - 운송회사 전송 배치 (IF_SHIP_001, 5분 주기)
 * <pre>
 *  [Scheduler Sender] → ORDER_TB 조회 (APPLICANT_KEY, STATUS='N')
 *                     → (매핑: 운송사 스키마 + SHIPMENT_ID 채번) → [JDBC Receiver] SHIPMENT_TB INSERT
 *                     → 성공 건만 ORDER_TB STATUS='Y'
 * </pre>
 * 주문 DB 와 운송 DB 는 서로 다른 DB 라 하나의 트랜잭션으로 묶을 수 없다. 그래서
 * <ul>
 *   <li><b>건 단위 처리</b>: 한 건 실패가 나머지 건을 막지 않는다. 실패 건은 STATUS='N' 으로 남아 다음 주기에 자동 재처리</li>
 *   <li><b>멱등성</b>: INSERT 전에 같은 ORDER_ID 가 SHIPMENT_TB 에 있는지 확인한다.
 *       (이전 주기에서 INSERT 는 됐지만 STATUS UPDATE 가 실패한 경우 → 중복 적재 없이 STATUS 만 맞춘다)</li>
 *   <li>DB 연결이 끊기면 남은 건은 중단하고 다음 주기에 맡긴다</li>
 * </ul>
 */
public final class ShipmentBatchFlow {

    public static final String INTERFACE_ID = "IF_SHIP_001";
    private static final String IF = INTERFACE_ID;
    private static final Logger log = AppLog.get(ShipmentBatchFlow.class);

    public record BatchResult(int total, int sent, int recovered, int failed, String error) {
        public Status status() {
            if (error != null) return Status.FAIL;
            if (total == 0) return Status.SKIP;
            if (failed == 0) return Status.SUCCESS;
            return sent + recovered == 0 ? Status.FAIL : Status.PARTIAL;
        }

        public String summary() {
            return "대상 " + total + "건 / 전송 " + sent + "건 / 복구 " + recovered + "건 / 실패 " + failed + "건"
                    + (error == null ? "" : " / 오류: " + error);
        }
    }

    private final String applicantKey;
    private final JdbcSupport jdbc;
    private final ConnectionInfo orderDb;
    private final ConnectionInfo shipmentDb;
    private final JdbcOrderReceiver orderReceiver;
    private final JdbcShipmentReceiver shipmentReceiver;
    private final ShipmentMapper mapper = new ShipmentMapper();

    public ShipmentBatchFlow(String applicantKey, JdbcSupport jdbc, ConnectionInfo orderDb, ConnectionInfo shipmentDb,
                             JdbcOrderReceiver orderReceiver, JdbcShipmentReceiver shipmentReceiver) {
        this.applicantKey = applicantKey;
        this.jdbc = jdbc;
        this.orderDb = orderDb;
        this.shipmentDb = shipmentDb;
        this.orderReceiver = orderReceiver;
        this.shipmentReceiver = shipmentReceiver;
    }

    public BatchResult run() {
        TxContext.begin(IF);
        long start = System.currentTimeMillis();
        int total = 0, sent = 0, recovered = 0, failed = 0;
        String error = null;
        Connection oc = null;
        Connection sc = null;
        try {
            MonitorLog.step(IF, "START", Status.START, null, "배치 시작");

            // Sender: 미전송 주문 조회
            oc = jdbc.open(orderDb);
            oc.setAutoCommit(true);
            List<PendingOrder> pending = orderReceiver.selectPending(oc, applicantKey);
            total = pending.size();
            MonitorLog.step(IF, "BATCH_SELECT", Status.SUCCESS, total, "미전송 주문 조회");
            if (pending.isEmpty()) {
                return finish(start, new BatchResult(0, 0, 0, 0, null));
            }

            sc = jdbc.open(shipmentDb);
            sc.setAutoCommit(true);
            int nextIdx = IdGenerator.nextIndex(shipmentReceiver.findShipmentIds(sc, applicantKey));

            for (PendingOrder p : pending) {
                try {
                    Optional<String> existing = shipmentReceiver.findShipmentIdByOrder(sc, applicantKey, p.orderId());
                    String shipmentId;
                    if (existing.isPresent()) {
                        shipmentId = existing.get();
                    } else {
                        shipmentId = insertWithRetry(sc, p, nextIdx);
                        nextIdx = IdGenerator.toIndex(shipmentId) + 1;
                    }
                    orderReceiver.markSent(oc, applicantKey, p.orderId());
                    if (existing.isPresent()) {
                        recovered++;
                        MonitorLog.step(IF, "SHIPMENT_RECOVER", Status.SUCCESS, 1,
                                p.orderId() + " 이미 적재됨(" + shipmentId + ") → STATUS 만 Y 로 보정");
                    } else {
                        sent++;
                        MonitorLog.step(IF, "SHIPMENT_INSERT", Status.SUCCESS, 1, p.orderId() + " → " + shipmentId);
                    }
                } catch (RuntimeException ex) {
                    EaiException e = EaiException.wrap(ex);
                    failed++;
                    MonitorLog.write(IF, "SHIPMENT_INSERT", Status.FAIL, 1, null, p.orderId() + ": " + e.getMessage(), e.errorCode());
                    log.warning("운송 적재 실패 " + p.orderId() + ": " + e.getMessage());
                    if (e.errorCode() == ErrorCode.JDBC_CONNECT) {
                        failed += total - (sent + recovered + failed);
                        error = "DB 연결 끊김으로 남은 건 다음 주기로 이월";
                        break;
                    }
                }
            }
            return finish(start, new BatchResult(total, sent, recovered, failed, error));
        } catch (Exception ex) {
            EaiException e = ex instanceof java.sql.SQLException
                    ? JdbcSupport.classify((java.sql.SQLException) ex, "배치")
                    : EaiException.wrap(ex);
            MonitorLog.fail(IF, "BATCH", e, null);
            log.warning("배치 실패 [" + e.errorCode().code() + "] " + e.getMessage());
            return finish(start, new BatchResult(total, sent, recovered, total - sent - recovered, e.getMessage()));
        } finally {
            JdbcSupport.closeQuietly(sc);
            JdbcSupport.closeQuietly(oc);
            TxContext.end();
        }
    }

    /** SHIPMENT_ID 중복(다른 프로세스가 먼저 사용) 시 ID 를 다시 계산해 1회 재시도 */
    private String insertWithRetry(Connection sc, PendingOrder p, int nextIdx) {
        String id = IdGenerator.fromIndex(nextIdx);
        try {
            shipmentReceiver.insert(sc, mapper.toShipment(p, id, applicantKey));
            return id;
        } catch (EaiException e) {
            if (e.errorCode() != ErrorCode.JDBC_DUPLICATE) throw e;
            id = IdGenerator.fromIndex(IdGenerator.nextIndex(shipmentReceiver.findShipmentIds(sc, applicantKey)));
            ShipmentRecord r = mapper.toShipment(p, id, applicantKey);
            shipmentReceiver.insert(sc, r);
            return id;
        }
    }

    private BatchResult finish(long start, BatchResult r) {
        MonitorLog.write(IF, "END", r.status(), r.total(), System.currentTimeMillis() - start, r.summary(), null);
        if (r.status() == Status.SKIP) {
            log.info("배치: 전송 대상 없음");
        } else {
            log.info("배치 완료: " + r.summary());
        }
        return r;
    }
}
