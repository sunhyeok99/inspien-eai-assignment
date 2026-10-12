package com.assignment.eai.model;

/** 배치가 ORDER_TB 에서 조회한 미전송(STATUS='N') 주문 */
public record PendingOrder(String orderId, String itemId, String address) {
}
