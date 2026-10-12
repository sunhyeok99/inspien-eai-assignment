package com.assignment.eai.flow;

import com.assignment.eai.common.EaiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 시나리오1 처리 결과 → 주문자에게 돌려줄 JSON 응답 */
public record OrderResult(boolean success, int httpStatus, String txId, String code, String message,
                          List<String> orderIds, String fileName, List<String> warnings) {

    public static final String INTERFACE_ID = "IF_ORDER_001";

    public static OrderResult ok(String txId, List<String> orderIds, String fileName, List<String> warnings) {
        return new OrderResult(true, 200, txId, "S000",
                "주문 " + orderIds.size() + "건 적재 및 영수증 전송 완료", orderIds, fileName, warnings);
    }

    public static OrderResult fail(String txId, EaiException e) {
        return new OrderResult(false, e.errorCode().httpStatus(), txId, e.errorCode().code(),
                e.errorCode().description() + ": " + e.getMessage(), List.of(), null, List.of());
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("interfaceId", INTERFACE_ID);
        m.put("txId", txId);
        m.put("result", success ? "SUCCESS" : "FAIL");
        m.put("code", code);
        m.put("message", message);
        if (success) {
            m.put("orderCount", orderIds.size());
            m.put("orderIds", orderIds);
            m.put("receiptFile", fileName);
            if (!warnings.isEmpty()) m.put("warnings", warnings);
        }
        return m;
    }
}
