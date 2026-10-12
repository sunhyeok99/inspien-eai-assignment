package com.assignment.eai.model;

/** 요청 XML 의 ITEM (주문 상품) */
public record OrderItem(String userId, String itemId, String itemName, String price) {
}
