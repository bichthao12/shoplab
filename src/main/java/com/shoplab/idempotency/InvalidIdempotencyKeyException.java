package com.shoplab.idempotency;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidIdempotencyKeyException extends ApiException {
    public InvalidIdempotencyKeyException() {
        super(HttpStatus.BAD_REQUEST, "invalid-idempotency-key", "Invalid Idempotency-Key",
                "Idempotency-Key phải dài 8–255 ký tự, chỉ gồm chữ, số, '-' và '_' (khuyến nghị dùng UUID)");
    }
}
