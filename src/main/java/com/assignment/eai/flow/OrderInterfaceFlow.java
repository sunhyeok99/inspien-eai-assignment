package com.assignment.eai.flow;

import com.assignment.eai.common.AppLog;
import com.assignment.eai.common.ConnectionInfo;
import com.assignment.eai.common.EaiException;
import com.assignment.eai.common.ErrorCode;
import com.assignment.eai.common.IdGenerator;
import com.assignment.eai.common.MonitorLog;
import com.assignment.eai.common.MonitorLog.Status;
import com.assignment.eai.common.TxContext;
import com.assignment.eai.mapper.OrderMapper;
import com.assignment.eai.mapper.OrderValidator;
import com.assignment.eai.mapper.OrderXmlParser;
import com.assignment.eai.mapper.ReceiptFileMapper;
import com.assignment.eai.model.OrderMessage;
import com.assignment.eai.model.OrderRecord;
import com.assignment.eai.model.ReceiptFile;
import com.assignment.eai.receiver.FtpReceiptReceiver;
import com.assignment.eai.receiver.JdbcOrderReceiver;
import com.assignment.eai.receiver.JdbcSupport;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 시나리오 1 - 주문 발생 실시간 처리 (IF_ORDER_001, 동기)
 * <pre>
 *  [REST Sender] → 파싱 → 검증 → (매핑: 채번 + 플랫 구조) → [JDBC Receiver] INSERT (미커밋)
 *                                                       → [FTP Receiver] 영수증 업로드
 *                                                       → COMMIT → 주문자에게 성공 응답
 * </pre>
 * <b>JDBC·FTP 동기화</b>: DB 와 FTP 는 하나의 트랜잭션으로 묶을 수 없으므로
 * DB 트랜잭션을 열어 둔 채 FTP 를 먼저 끝내고, 둘 다 성공했을 때만 COMMIT 한다.
 * <ul>
 *   <li>INSERT 실패 → ROLLBACK, FTP 미전송</li>
 *   <li>FTP 실패 → ROLLBACK (DB 에 남지 않음)</li>
 *   <li>COMMIT 실패 → 업로드한 파일 삭제(보상). 삭제도 실패하면 수동 조치 대상으로 모니터링 로그에 남김</li>
 * </ul>
 * 채번(최댓값+1)이 동시 요청에서 겹치지 않도록 적재 구간은 lock 으로 직렬화하고,
 * 그래도 PK 중복(다른 프로세스 등)이 나면 ID 를 다시 계산해 재시도한다.
 */
public final class OrderInterfaceFlow {

    private static final Logger log = AppLog.get(OrderInterfaceFlow.class);
    private static final String IF = OrderResult.INTERFACE_ID;
    private static final int DUPLICATE_RETRY = 3;

    private final String applicantKey;
    private final String participantName;
    private final Charset receiptCharset;
    private final JdbcSupport jdbc;
    private final ConnectionInfo orderDb;
    private final JdbcOrderReceiver orderReceiver;
    private final FtpReceiptReceiver ftpReceiver;
    private final Supplier<LocalDateTime> clock;

    private final OrderXmlParser parser = new OrderXmlParser();
    private final OrderValidator validator = new OrderValidator();
    private final OrderMapper mapper = new OrderMapper();
    private final ReceiptFileMapper receiptMapper = new ReceiptFileMapper();
    private final ReentrantLock lock = new ReentrantLock(true);
    private String lastFileName;

    public OrderInterfaceFlow(String applicantKey, String participantName, Charset receiptCharset, JdbcSupport jdbc,
                              ConnectionInfo orderDb, JdbcOrderReceiver orderReceiver, FtpReceiptReceiver ftpReceiver,
                              Supplier<LocalDateTime> clock) {
        this.applicantKey = applicantKey;
        this.participantName = participantName;
        this.receiptCharset = receiptCharset;
        this.jdbc = jdbc;
        this.orderDb = orderDb;
        this.orderReceiver = orderReceiver;
        this.ftpReceiver = ftpReceiver;
        this.clock = clock;
    }

    public OrderResult process(byte[] body, String contentTypeCharset) {
        String txId = TxContext.begin(IF);
        long start = System.currentTimeMillis();
        String step = "RECEIVE";
        try {
            MonitorLog.step(IF, step, Status.START, null, "요청 수신 " + body.length + " bytes");

            step = "VALIDATE";
            OrderMessage msg = parser.parse(body, contentTypeCharset);
            List<String> warnings = validator.validate(msg);
            MonitorLog.step(IF, step, Status.SUCCESS, msg.items().size(),
                    "HEADER " + msg.headers().size() + "건 / ITEM " + msg.items().size() + "건");
            warnings.forEach(w -> log.warning(w));

            step = "LOAD";
            lock.lock();
            try {
                OrderResult result = loadAndSend(msg, txId, warnings);
                MonitorLog.write(IF, "END", Status.SUCCESS, result.orderIds().size(),
                        System.currentTimeMillis() - start, result.message() + " " + result.orderIds(), null);
                log.info("처리 완료 " + result.orderIds() + " / " + result.fileName());
                return result;
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException ex) {
            EaiException e = EaiException.wrap(ex);
            long elapsed = System.currentTimeMillis() - start;
            MonitorLog.fail(IF, step, e, null);
            MonitorLog.write(IF, "END", Status.FAIL, null, elapsed, e.getMessage(), e.errorCode());
            MonitorLog.savePayload(body, "xml");
            log.log(e.errorCode() == ErrorCode.UNKNOWN ? java.util.logging.Level.SEVERE : java.util.logging.Level.WARNING,
                    "처리 실패 [" + e.errorCode().code() + "] " + e.getMessage(), e.errorCode() == ErrorCode.UNKNOWN ? e : null);
            return OrderResult.fail(txId, e);
        } finally {
            TxContext.end();
        }
    }

    private OrderResult loadAndSend(OrderMessage msg, String txId, List<String> warnings) {
        for (int attempt = 1; ; attempt++) {
            Connection c = jdbc.open(orderDb);
            try {
                setAutoCommit(c, false);

                // 매핑: 채번 + HEADER/ITEM → ORDER_TB 플랫 구조
                int nextIdx = IdGenerator.nextIndex(orderReceiver.findOrderIds(c, applicantKey));
                List<OrderRecord> records = mapper.toRecords(msg, applicantKey, nextIdx);
                List<String> ids = records.stream().map(OrderRecord::orderId).toList();

                // Receiver 1: JDBC (아직 COMMIT 하지 않음)
                orderReceiver.insert(c, records);
                MonitorLog.step(IF, "JDBC_ORDER", Status.SUCCESS, records.size(), "INSERT(미커밋) " + ids);

                // Receiver 2: FTP
                ReceiptFile file = receiptMapper.map(records, participantName, nextFileTime(), receiptCharset);
                ftpReceiver.upload(file);
                MonitorLog.step(IF, "FTP_RECEIPT", Status.SUCCESS, file.lineCount(), file.fileName());

                // 둘 다 성공 → COMMIT
                commitOrCompensate(c, file);
                MonitorLog.step(IF, "JDBC_COMMIT", Status.SUCCESS, records.size(), null);
                return OrderResult.ok(txId, ids, file.fileName(), warnings);
            } catch (RuntimeException ex) {
                JdbcSupport.rollbackQuietly(c);
                EaiException e = EaiException.wrap(ex);
                if (e.errorCode() == ErrorCode.JDBC_DUPLICATE && attempt < DUPLICATE_RETRY) {
                    log.warning("ORDER_ID 중복, ID 재계산 후 재시도(" + attempt + "): " + e.getMessage());
                    continue;
                }
                throw e;
            } finally {
                JdbcSupport.closeQuietly(c);
            }
        }
    }

    private void commitOrCompensate(Connection c, ReceiptFile file) {
        try {
            c.commit();
        } catch (SQLException commitError) {
            EaiException e = JdbcSupport.classify(commitError, "ORDER_TB COMMIT");
            try {
                ftpReceiver.delete(file.fileName());
                MonitorLog.step(IF, "FTP_COMPENSATE", Status.COMPENSATED, null, "COMMIT 실패로 영수증 삭제: " + file.fileName());
            } catch (RuntimeException deleteError) {
                throw new EaiException(ErrorCode.COMPENSATION,
                        "COMMIT 실패 후 영수증 삭제도 실패 - FTP 파일 수동 삭제 필요: " + file.fileName()
                                + " (commit: " + commitError.getMessage() + ", delete: " + deleteError.getMessage() + ")", e);
            }
            throw e;
        }
    }

    /** 파일명이 초 단위라 같은 초에 두 요청이 오면 덮어쓰게 되므로, 직전 파일명과 같으면 다음 초까지 대기 */
    private LocalDateTime nextFileTime() {
        LocalDateTime now = clock.get();
        while (ReceiptFileMapper.fileName(participantName, now).equals(lastFileName)) {
            long waitMs = 1000 - now.getNano() / 1_000_000 + 5;
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EaiException(ErrorCode.UNKNOWN, "대기 중 인터럽트", e);
            }
            now = clock.get();
        }
        lastFileName = ReceiptFileMapper.fileName(participantName, now.truncatedTo(ChronoUnit.SECONDS));
        return now;
    }

    private static void setAutoCommit(Connection c, boolean value) {
        try {
            c.setAutoCommit(value);
        } catch (SQLException e) {
            throw JdbcSupport.classify(e, "autoCommit 설정");
        }
    }
}
