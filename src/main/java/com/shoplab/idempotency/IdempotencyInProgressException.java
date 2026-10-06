package com.shoplab.idempotency;

public class IdempotencyInProgressException extends RuntimeException {
    public IdempotencyInProgressException(String key) {
        super("Request với Idempotency-Key '" + key + "' đang được xử lý, hãy thử lại sau");
    }
}
