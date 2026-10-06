package com.shoplab.idempotency;

public class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '" + key + "' đã được dùng cho một request có nội dung khác");
    }
}
