package com.assignment.eai.model;

/** 요청 XML 의 HEADER (주문자). HEADER : ITEM = 1 : N (USER_ID 로 연결) */
public record OrderHeader(String userId, String name, String address, String status) {
}
