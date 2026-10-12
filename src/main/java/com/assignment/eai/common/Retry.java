package com.assignment.eai.common;

import java.util.logging.Logger;

/**
 * 일시적 장애(연결 실패 등, ErrorCode.retryable=true)에 한해 지수 백오프로 재시도한다.
 * 데이터 오류처럼 재시도해도 결과가 같은 오류는 즉시 실패 처리한다.
 */
public final class Retry {

    private static final Logger log = AppLog.get(Retry.class);

    @FunctionalInterface
    public interface Action<T> {
        T run() throws Exception;
    }

    private Retry() {
    }

    public static <T> T call(String what, int maxAttempts, long backoffMs, Action<T> action) {
        EaiException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.run();
            } catch (Exception e) {
                last = EaiException.wrap(e);
                if (!last.errorCode().retryable() || attempt == maxAttempts) {
                    break;
                }
                long wait = backoffMs * (1L << (attempt - 1));
                log.warning(what + " 실패(" + attempt + "/" + maxAttempts + "), " + wait + "ms 후 재시도: " + last.getMessage());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }
}
