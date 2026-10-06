package com.shoplab.idempotency;

/**
 * @param body     dữ liệu trả về
 * @param status   HTTP status (giống hệt lần đầu khi replay)
 * @param replayed true nếu đây là kết quả của lần gửi trước
 */
public record IdempotentResult<T>(T body, int status, boolean replayed) {}
