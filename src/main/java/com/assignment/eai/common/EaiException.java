package com.assignment.eai.common;

/** 연계 처리 공통 예외. 어느 단계에서 어떤 종류의 오류가 났는지를 ErrorCode 로 표현한다. */
public class EaiException extends RuntimeException {

    private final ErrorCode errorCode;

    public EaiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public EaiException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 어떤 예외든 EaiException 으로 정규화 */
    public static EaiException wrap(Throwable t) {
        if (t instanceof EaiException) {
            return (EaiException) t;
        }
        return new EaiException(ErrorCode.UNKNOWN, t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }
}
