package com.shoplab.idempotency;

/** Một dòng trong bảng idempotency_keys (map tự động từ tên cột snake_case). */
record IdempotencyRecord(
        String idemKey,
        String requestHash,
        String status,
        Integer responseStatus,
        String responseHeaders,  // JSON: tên header → danh sách giá trị
        String responseBody      // nguyên văn body đã trả lần đầu
) {
    boolean isCompleted() {
        return "COMPLETED".equals(status);
    }
}
