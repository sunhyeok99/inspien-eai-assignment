package com.assignment.eai.mapper;

import com.assignment.eai.common.EaiException;
import com.assignment.eai.common.ErrorCode;
import com.assignment.eai.model.OrderHeader;
import com.assignment.eai.model.OrderItem;
import com.assignment.eai.model.OrderMessage;
import com.assignment.eai.model.SkippedItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 주문 요청 유효성 검증.
 * <ul>
 *   <li>XML 형식·필수값·PRICE 형식·구분자 오류 → 하나라도 있으면 전체 메시지 거부(E100). 오류는 한 번에 모두 모아 반환</li>
 *   <li>HEADER 가 없는 ITEM → 해당 ITEM 만 제외하고 나머지는 계속 처리 (NAME/ADDRESS 를 채울 수 없어 적재 불가)</li>
 *   <li>제외 후 남는 ITEM 이 없으면 전체 거부</li>
 * </ul>
 */
public final class OrderValidator {

    private static final Pattern PRICE = Pattern.compile("^\\d{1,15}$");

    /**
     * @param accepted 제외 건을 뺀 처리 대상 메시지
     * @param skipped  제외된 ITEM (HEADER 없음)
     * @param warnings 경고 (처리는 계속)
     */
    public record Result(OrderMessage accepted, List<SkippedItem> skipped, List<String> warnings) {
    }

    public Result validate(OrderMessage msg) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<OrderItem> acceptedItems = new ArrayList<>();
        List<SkippedItem> skipped = new ArrayList<>();

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
                    skipped.add(new SkippedItem(it.userId(), it.itemId(), "USER_ID(" + it.userId() + ") 에 해당하는 HEADER 가 없습니다."));
                    continue;
                }
                acceptedItems.add(it);
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

        if (errors.isEmpty() && !msg.items().isEmpty() && acceptedItems.isEmpty()) {
            errors.add("HEADER 와 연결되는 ITEM 이 없습니다 (제외 " + skipped.size() + "건).");
        }
        if (!errors.isEmpty()) {
            throw new EaiException(ErrorCode.VALIDATION, String.join(" / ", errors));
        }
        return new Result(new OrderMessage(msg.headers(), List.copyOf(acceptedItems)), List.copyOf(skipped), warnings);
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
