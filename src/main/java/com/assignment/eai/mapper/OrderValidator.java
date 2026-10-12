package com.assignment.eai.mapper;

import com.assignment.eai.common.EaiException;
import com.assignment.eai.common.ErrorCode;
import com.assignment.eai.model.OrderHeader;
import com.assignment.eai.model.OrderItem;
import com.assignment.eai.model.OrderMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 주문 요청 유효성 검증. 하나라도 위반하면 전체 메시지를 거부한다(부분 적재 방지).
 * 오류는 한 번에 모두 모아 반환해 주문자가 한 번에 수정할 수 있게 한다.
 */
public final class OrderValidator {

    private static final Pattern PRICE = Pattern.compile("^\\d{1,15}$");

    /** @return 경고 목록 (처리는 계속) */
    public List<String> validate(OrderMessage msg) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (msg.headers().isEmpty()) errors.add("HEADER 가 없습니다.");
        if (msg.items().isEmpty()) errors.add("ITEM 이 없습니다.");

        Set<String> headerUsers = new HashSet<>();
        for (int i = 0; i < msg.headers().size(); i++) {
            OrderHeader h = msg.headers().get(i);
            String at = "HEADER[" + (i + 1) + "]";
            required(errors, at, "USER_ID", h.userId());
            required(errors, at, "NAME", h.name());
            required(errors, at, "ADDRESS", h.address());
            safe(errors, at, h.userId(), h.name(), h.address(), h.status());
            if (h.status() != null && !h.status().isEmpty() && !"N".equals(h.status())) {
                errors.add(at + " STATUS 는 신규 주문(N) 이어야 합니다: " + h.status());
            }
            if (h.userId() != null && !h.userId().isEmpty() && !headerUsers.add(h.userId())) {
                errors.add(at + " USER_ID 중복: " + h.userId());
            }
        }

        Set<String> usersWithItem = new HashSet<>();
        Set<String> userItemPairs = new HashSet<>();
        for (int i = 0; i < msg.items().size(); i++) {
            OrderItem it = msg.items().get(i);
            String at = "ITEM[" + (i + 1) + "]";
            required(errors, at, "USER_ID", it.userId());
            required(errors, at, "ITEM_ID", it.itemId());
            required(errors, at, "ITEM_NAME", it.itemName());
            required(errors, at, "PRICE", it.price());
            safe(errors, at, it.userId(), it.itemId(), it.itemName(), it.price());
            if (it.price() != null && !it.price().isEmpty() && !PRICE.matcher(it.price()).matches()) {
                errors.add(at + " PRICE 는 0 이상의 정수여야 합니다: " + it.price());
            }
            if (it.userId() != null && !it.userId().isEmpty()) {
                if (!headerUsers.contains(it.userId())) {
                    errors.add(at + " USER_ID(" + it.userId() + ") 에 해당하는 HEADER 가 없습니다.");
                }
                usersWithItem.add(it.userId());
                if (!userItemPairs.add(it.userId() + "|" + it.itemId())) {
                    warnings.add(at + " 같은 주문자의 동일 상품이 중복 요청되었습니다: " + it.userId() + "/" + it.itemId());
                }
            }
        }
        for (String u : headerUsers) {
            if (!usersWithItem.contains(u)) {
                warnings.add("USER_ID(" + u + ") HEADER 에 ITEM 이 없어 적재 대상에서 제외됩니다.");
            }
        }

        if (!errors.isEmpty()) {
            throw new EaiException(ErrorCode.VALIDATION, String.join(" / ", errors));
        }
        return warnings;
    }

    private static void required(List<String> errors, String at, String field, String value) {
        if (value == null || value.isEmpty()) {
            errors.add(at + " " + field + " 누락");
        }
    }

    /** 영수증 파일 구분자(^)·줄바꿈이 값에 있으면 파일 포맷이 깨지므로 거부 */
    private static void safe(List<String> errors, String at, String... values) {
        for (String v : values) {
            if (v != null && (v.indexOf('^') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0)) {
                errors.add(at + " 값에 구분자(^) 또는 줄바꿈을 사용할 수 없습니다: " + v.replace("\n", "\\n"));
            }
        }
    }
}
