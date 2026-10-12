package com.assignment.eai.model;

import java.util.List;

/** Sender 가 수신한 주문 요청 메시지 (계층 구조) */
public record OrderMessage(List<OrderHeader> headers, List<OrderItem> items) {
}
