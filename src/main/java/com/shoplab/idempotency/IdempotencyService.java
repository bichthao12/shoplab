package com.shoplab.idempotency;

import org.springframework.http.ResponseEntity;

import java.util.function.Supplier;

/**
 * API của module idempotency: chạy một request ghi đúng MỘT lần theo header Idempotency-Key.
 *
 *  - Lần đầu: chạy action, lưu nguyên văn response (status, header, body) cùng transaction với nghiệp vụ.
 *  - Gửi lại cùng key, cùng nội dung → trả lại y hệt response đã lưu, kèm header Idempotent-Replayed: true.
 *  - Cùng key, nội dung khác → IdempotencyKeyReusedException (422).
 *  - Key sai định dạng → InvalidIdempotencyKeyException (400).
 *  - Request khác đang giữ cùng key quá lock_timeout → 409 + Retry-After.
 */
public interface IdempotencyService {

    String HEADER = "Idempotency-Key";
    String REPLAYED_HEADER = "Idempotent-Replayed";

    /**
     * @param key     giá trị header Idempotency-Key
     * @param request request ở dạng chuẩn hoá (cùng nội dung → cùng giá trị), dùng để phát hiện key bị dùng lại
     * @param action  nghiệp vụ, chạy CHUNG transaction với việc ghi key; lỗi → rollback cả hai
     * @return response lần đầu; gửi lại cùng key → bản lưu y hệt, kèm header Idempotent-Replayed: true
     */
    ResponseEntity<String> execute(String key, Object request, Supplier<ResponseEntity<?>> action);
}
