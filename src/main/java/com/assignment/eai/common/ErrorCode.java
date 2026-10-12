package com.assignment.eai.common;

/**
 * 연계 처리 중 발생할 수 있는 오류 분류.
 * retryable: 일시적 장애(네트워크/연결)라 재시도로 회복 가능성이 있는지 여부
 * httpStatus: REST Sender 가 주문자에게 돌려줄 HTTP 상태
 */
public enum ErrorCode {
    VALIDATION("E100", "요청 데이터 검증 실패", false, 400),
    MAPPING("E200", "데이터 변환 실패", false, 400),
    ID_EXHAUSTED("E210", "채번 가능한 ID 소진", false, 500),
    JDBC_CONNECT("E300", "DB 연결 실패", true, 503),
    JDBC_SQL("E310", "DB 처리 실패", false, 500),
    JDBC_DUPLICATE("E320", "DB PK 중복", true, 500),
    FTP_CONNECT("E400", "FTP 연결 실패", true, 503),
    FTP_LOGIN("E410", "FTP 로그인 실패", false, 503),
    FTP_TRANSFER("E420", "FTP 전송 실패", true, 502),
    COMPENSATION("E500", "보상 처리 실패 - 수동 조치 필요", false, 500),
    CONFIG("E900", "설정/접속 정보 오류", false, 500),
    UNKNOWN("E999", "알 수 없는 오류", false, 500);

    private final String code;
    private final String description;
    private final boolean retryable;
    private final int httpStatus;

    ErrorCode(String code, String description, boolean retryable, int httpStatus) {
        this.code = code;
        this.description = description;
        this.retryable = retryable;
        this.httpStatus = httpStatus;
    }

    public String code() { return code; }
    public String description() { return description; }
    public boolean retryable() { return retryable; }
    public int httpStatus() { return httpStatus; }
}
