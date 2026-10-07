package com.shoplab.idempotency;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** 409 + Retry-After: báo client đợi 1 giây rồi gửi lại với CÙNG key. */
public class IdempotencyInProgressException extends ApiException {
    public IdempotencyInProgressException(String key) {
        super(HttpStatus.CONFLICT, "idempotency-in-progress", "Request In Progress",
                "Request với Idempotency-Key '" + key + "' đang được xử lý, hãy thử lại sau");
        addHeader(HttpHeaders.RETRY_AFTER, "1");
    }
}
