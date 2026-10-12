package com.assignment.eai.mapper;

import com.assignment.eai.common.IdGenerator;
import com.assignment.eai.model.OrderHeader;
import com.assignment.eai.model.OrderItem;
import com.assignment.eai.model.OrderMessage;
import com.assignment.eai.model.OrderRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 계층 구조(HEADER 1 : ITEM N) → ORDER_TB 플랫 구조 매핑.
 * <pre>
 *  ITEM 1건마다 1행을 만들고, ITEM.USER_ID 로 HEADER 를 찾아 NAME/ADDRESS/STATUS 를 채운다.
 *  ORDER_ID 는 행마다 새로 채번 (PK 가 ORDER_ID+APPLICANT_KEY 뿐이라 한 주문자가 여러 상품을 사도 행마다 달라야 함)
 *  APPLICANT_KEY 는 지원자 키 고정, STATUS 는 비어 있으면 'N'(미전송)
 * </pre>
 */
public final class OrderMapper {

    public List<OrderRecord> toRecords(OrderMessage msg, String applicantKey, int startIdIndex) {
        Map<String, OrderHeader> headerByUser = new LinkedHashMap<>();
        for (OrderHeader h : msg.headers()) {
            headerByUser.put(h.userId(), h);
        }
        List<OrderRecord> records = new ArrayList<>();
        int idx = startIdIndex;
        for (OrderItem item : msg.items()) {
            OrderHeader h = headerByUser.get(item.userId());
            String status = h.status() == null || h.status().isEmpty() ? "N" : h.status();
            records.add(new OrderRecord(
                    IdGenerator.fromIndex(idx++),
                    applicantKey,
                    item.userId(),
                    item.itemId(),
                    h.name(),
                    h.address(),
                    item.itemName(),
                    item.price(),
                    status));
        }
        return records;
    }
}
