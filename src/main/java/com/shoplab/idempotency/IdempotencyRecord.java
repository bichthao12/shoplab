package com.shoplab.idempotency;

/** Một dòng trong bảng idempotency_keys (map tự động từ tên cột snake_case). */
public record IdempotencyRecord(
        String idemKey,
        String requestHash,
        String status,
        Integer responseStatus,
        String responseBody      // JSON
) {
    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }
}
