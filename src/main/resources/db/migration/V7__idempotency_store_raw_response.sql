-- Lưu response NGUYÊN VĂN để trả lại y hệt khi client gửi lại cùng key:
--  - response_body đổi JSONB → TEXT: JSONB tự sắp lại thứ tự key và bỏ khoảng trắng, nên không còn giống hệt bản gốc
--  - thêm response_headers (vd Location) để bản replay có đủ header như lần đầu
ALTER TABLE idempotency_keys
    ALTER COLUMN response_body TYPE TEXT USING response_body::text,
    ADD COLUMN response_headers JSONB;
