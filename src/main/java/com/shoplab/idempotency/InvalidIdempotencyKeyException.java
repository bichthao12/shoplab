package com.shoplab.idempotency;

public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key phải dài 8–255 ký tự, chỉ gồm chữ, số, '-' và '_' (khuyến nghị dùng UUID)");
    }
}
