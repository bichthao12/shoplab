package com.shoplab.idempotency.internal;

/** Trạng thái của một key. Lưu dạng chuỗi, khớp CHECK ck_idempotency_status trong DB. */
enum IdempotencyStatus {
    /** Request giữ key đang chạy (transaction chưa commit). */
    IN_PROGRESS,
    /** Đã xong, response đã được lưu để trả lại cho các lần gửi lại. */
    COMPLETED
}
