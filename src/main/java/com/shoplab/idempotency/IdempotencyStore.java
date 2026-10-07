package com.shoplab.idempotency;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Truy cập bảng idempotency_keys bằng JdbcClient. Chỉ dùng trong package idempotency.
 * JdbcClient dùng chung DataSource với JPA, nên khi được gọi trong một @Transactional
 * nó chạy trên CÙNG connection / CÙNG transaction với các thao tác JPA (tạo đơn, trừ kho).
 */
@Repository
class IdempotencyStore {

    private final JdbcClient jdbc;

    IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * "Giành" key bằng INSERT ... ON CONFLICT DO NOTHING.
     *
     * Nếu một transaction khác đang giữ cùng key mà CHƯA commit, Postgres sẽ CHỜ (tối đa lock_timeout):
     *  - transaction kia commit   → trả về 0 (key đã có)  → đọc kết quả đã lưu để replay
     *  - transaction kia rollback → INSERT thành công      → request này được xử lý
     * @return true nếu giành được key
     */
    boolean tryClaim(String key, String requestHash) {
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys (idem_key, request_hash, status)
                        VALUES (:key, :hash, 'IN_PROGRESS')
                        ON CONFLICT (idem_key) DO NOTHING
                        """)
                .param("key", key)
                .param("hash", requestHash)
                .update();
        return inserted == 1;
    }

    Optional<IdempotencyRecord> find(String key) {
        return jdbc.sql("""
                        SELECT idem_key, request_hash, status, response_status,
                               response_headers::text AS response_headers, response_body
                        FROM idempotency_keys
                        WHERE idem_key = :key
                        """)
                .param("key", key)
                .query(IdempotencyRecord.class)
                .optional();
    }

    /** Lưu nguyên văn response (status, header, body) để trả lại y hệt cho các lần retry. */
    void complete(String key, int responseStatus, String responseHeadersJson, String responseBody) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET status = 'COMPLETED',
                            response_status = :st,
                            response_headers = CAST(:headers AS jsonb),
                            response_body = :body
                        WHERE idem_key = :key
                        """)
                .param("st", responseStatus)
                .param("headers", responseHeadersJson)
                .param("body", responseBody)
                .param("key", key)
                .update();
    }

    int deleteOlderThanHours(int hours) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < now() - make_interval(hours => :h)")
                .param("h", hours)
                .update();
    }
}
