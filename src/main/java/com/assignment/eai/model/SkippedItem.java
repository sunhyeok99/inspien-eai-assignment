package com.assignment.eai.model;

/** 검증에서 제외된 ITEM (메시지 전체는 계속 처리). ORDER_ID 를 채번하지 않는다. */
public record SkippedItem(String userId, String itemId, String reason) {
}
