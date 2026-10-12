package com.assignment.eai.model;

/**
 * ORDER_TB 한 행 (HEADER + ITEM 을 펼친 플랫 구조). ITEM 1건 = 1행.
 * PK = (ORDER_ID, APPLICANT_KEY)
 */
public record OrderRecord(String orderId, String applicantKey, String userId, String itemId,
                          String name, String address, String itemName, String price, String status) {
}
