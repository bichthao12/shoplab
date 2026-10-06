-- Lưu nguyên nội dung phản hồi (JSON) thay vì chỉ lưu id tài nguyên.
-- Cấu trúc cuối cùng của bảng:
--   idem_key         VARCHAR(255) PRIMARY KEY   -- key (unique)
--   request_hash     VARCHAR(64)                -- mã băm request (SHA-256)
--   status           VARCHAR(20)                -- IN_PROGRESS | COMPLETED
--   response_status  INTEGER                    -- mã phản hồi HTTP (vd 201)
--   response_body    JSONB                      -- nội dung phản hồi
--   created_at       TIMESTAMPTZ

-- Key cũ không có response_body → xoá (key chỉ sống 24h, không ảnh hưởng dữ liệu đơn hàng)
DELETE FROM idempotency_keys;

ALTER TABLE idempotency_keys DROP COLUMN resource_id;
ALTER TABLE idempotency_keys ADD COLUMN response_body JSONB;

-- Đã COMPLETED thì bắt buộc có đủ mã phản hồi + nội dung phản hồi
ALTER TABLE idempotency_keys
    ADD CONSTRAINT ck_idempotency_completed_has_response
    CHECK (status <> 'COMPLETED' OR (response_status IS NOT NULL AND response_body IS NOT NULL));
