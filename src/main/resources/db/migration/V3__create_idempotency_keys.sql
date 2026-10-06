-- Lưu Idempotency-Key của các request ghi (POST /api/orders...)
CREATE TABLE idempotency_keys (
    idem_key         VARCHAR(255) PRIMARY KEY,
    request_hash     VARCHAR(64)  NOT NULL,          -- SHA-256 của body đã chuẩn hoá
    status           VARCHAR(20)  NOT NULL,          -- IN_PROGRESS | COMPLETED
    resource_id      BIGINT,                         -- id tài nguyên đã tạo (vd: order id)
    response_status  INTEGER,                        -- status code đã trả lần đầu (vd: 201)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX idx_idempotency_created_at ON idempotency_keys (created_at);
