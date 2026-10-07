package com.shoplab.idempotency;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

public class IdempotencyKeyReusedException extends ApiException {
    public IdempotencyKeyReusedException(String key) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reused", "Idempotency Key Reused",
                "Idempotency-Key '" + key + "' đã được dùng cho một request có nội dung khác");
    }
}
