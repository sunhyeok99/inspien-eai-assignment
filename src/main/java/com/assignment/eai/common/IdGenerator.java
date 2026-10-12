package com.assignment.eai.common;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * ORDER_ID / SHIPMENT_ID 채번 규칙: 알파벳 대문자 1개 + 숫자 3개 (A000 ~ Z999, 총 26,000개).
 * <p>
 * DB 자동 채번이 아니므로 "현재 사용 중인 ID 중 최댓값 + 1" 방식으로 순차 발급한다.
 * A999 다음은 B000. 순번(index) = (알파벳 - 'A') * 1000 + 숫자.
 * 다른 지원자 데이터와 섞이지 않도록 조회는 항상 APPLICANT_KEY 조건으로 한정한다(PK 가 ID + APPLICANT_KEY).
 */
public final class IdGenerator {

    public static final Pattern ID_PATTERN = Pattern.compile("^[A-Z][0-9]{3}$");
    private static final int MAX_INDEX = 26 * 1000 - 1;

    private IdGenerator() {
    }

    public static int toIndex(String id) {
        return (id.charAt(0) - 'A') * 1000 + Integer.parseInt(id.substring(1));
    }

    public static String fromIndex(int index) {
        if (index < 0 || index > MAX_INDEX) {
            throw new EaiException(ErrorCode.ID_EXHAUSTED, "채번 가능한 ID(A000~Z999)를 모두 사용했습니다.");
        }
        return (char) ('A' + index / 1000) + String.format("%03d", index % 1000);
    }

    /** 기존 ID 목록에서 다음 ID. 규칙에 맞지 않는 ID 는 무시한다. */
    public static int nextIndex(Collection<String> existingIds) {
        int max = -1;
        for (String id : existingIds) {
            if (id != null && ID_PATTERN.matcher(id.trim()).matches()) {
                max = Math.max(max, toIndex(id.trim()));
            }
        }
        return max + 1;
    }
}
