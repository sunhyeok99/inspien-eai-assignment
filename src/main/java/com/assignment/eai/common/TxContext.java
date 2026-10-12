package com.assignment.eai.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 요청(메시지) 단위 추적 ID. 한 요청의 모든 로그에 같은 txId 가 찍혀 처리 이력을 따라갈 수 있다.
 * 형식: {인터페이스ID}-{yyyyMMddHHmmssSSS}-{순번}
 */
public final class TxContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private TxContext() {
    }

    public static String begin(String interfaceId) {
        String id = interfaceId + "-" + LocalDateTime.now().format(FMT) + "-" + String.format("%04d", SEQ.incrementAndGet() % 10000);
        CURRENT.set(id);
        return id;
    }

    public static String current() {
        String id = CURRENT.get();
        return id == null ? "-" : id;
    }

    public static void end() {
        CURRENT.remove();
    }
}
