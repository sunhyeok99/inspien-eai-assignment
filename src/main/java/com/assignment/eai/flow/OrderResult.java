package com.assignment.eai.flow;

import com.assignment.eai.common.EaiException;
import com.assignment.eai.model.SkippedItem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 시나리오1 처리 결과 → 주문자에게 돌려줄 JSON 응답.
 * 일부 ITEM 이 제외된 경우(HEADER 없음)도 나머지는 처리되므로 HTTP 200, result = PARTIAL 로 구분한다.
 */
public record OrderResult(boolean success, int httpStatus, String txId, String code, String message,
                          List<String> orderIds, String fileName, List<String> warnings, List<SkippedItem> skipped) {

    public static final String INTERFACE_ID = "IF_ORDER_001";

    public static OrderResult ok(String txId, List<String> orderIds, String fileName, List<String> warnings,
                                 List<SkippedItem> skipped) {
        if (skipped.isEmpty()) {
            return new OrderResult(true, 200, txId, "S000",
                    "주문 " + orderIds.size() + "건 적재 및 영수증 전송 완료", orderIds, fileName, warnings, skipped);
        }
        return new OrderResult(true, 200, txId, "S001",
                "주문 " + orderIds.size() + "건 적재 및 영수증 전송 완료, HEADER 없는 ITEM " + skipped.size() + "건 제외",
                orderIds, fileName, warnings, skipped);
    }

    public static OrderResult fail(String txId, EaiException e) {
        return new OrderResult(false, e.errorCode().httpStatus(), txId, e.errorCode().code(),
                e.errorCode().description() + ": " + e.getMessage(), List.of(), null, List.of(), List.of());
    }

    public boolean partial() {
        return success && !skipped.isEmpty();
    }

    public String resultName() {
        return !success ? "FAIL" : partial() ? "PARTIAL" : "SUCCESS";
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("interfaceId", INTERFACE_ID);
        m.put("txId", txId);
        m.put("result", resultName());
        m.put("code", code);
        m.put("message", message);
        if (success) {
            m.put("orderCount", orderIds.size());
            m.put("orderIds", orderIds);
            m.put("receiptFile", fileName);
            if (partial()) {
                m.put("skippedCount", skipped.size());
                m.put("skippedItems", skipped.stream().map(s -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("userId", s.userId());
                    item.put("itemId", s.itemId());
                    item.put("reason", s.reason());
                    return item;
                }).toList());
            }
            if (!warnings.isEmpty()) m.put("warnings", warnings);
        }
        return m;
    }
}
