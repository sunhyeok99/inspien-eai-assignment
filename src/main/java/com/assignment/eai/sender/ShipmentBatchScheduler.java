package com.assignment.eai.sender;

import com.assignment.eai.common.AppLog;
import com.assignment.eai.flow.ShipmentBatchFlow;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Scheduler Sender - 5분 주기로 배치(시나리오 2)를 기동하는 연결 지점.
 * <ul>
 *   <li>단일 스레드라 이전 배치가 길어져도 두 배치가 겹쳐 실행되지 않는다(중복 전송 방지)</li>
 *   <li>ScheduledExecutorService 는 작업에서 예외가 새어 나가면 이후 실행을 모두 취소하므로
 *       모든 예외를 잡아 다음 주기가 계속 돌도록 한다</li>
 * </ul>
 */
public final class ShipmentBatchScheduler {

    private static final Logger log = AppLog.get(ShipmentBatchScheduler.class);

    private final ShipmentBatchFlow flow;
    private final long initialDelaySeconds;
    private final long intervalSeconds;
    private ScheduledExecutorService scheduler;

    public ShipmentBatchScheduler(ShipmentBatchFlow flow, long initialDelaySeconds, long intervalSeconds) {
        this.flow = flow;
        this.initialDelaySeconds = initialDelaySeconds;
        this.intervalSeconds = intervalSeconds;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shipment-batch");
            t.setDaemon(false);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::runSafely, initialDelaySeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Scheduler Sender 시작: " + initialDelaySeconds + "초 후 첫 실행, 이후 " + intervalSeconds + "초 주기");
    }

    private void runSafely() {
        try {
            flow.run();
        } catch (Throwable t) {
            log.log(Level.SEVERE, "배치 실행 중 예상치 못한 오류 (다음 주기는 계속 실행)", t);
        }
    }

    public void stop() {
        if (scheduler == null) return;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Scheduler Sender 종료");
    }
}
