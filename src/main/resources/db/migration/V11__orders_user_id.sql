-- Gắn đơn hàng với người đặt. Chỉ lưu id, không có khoá ngoại sang users (bảng của module user, xem V10).
-- Đơn tạo trước migration này không biết của ai nên để NULL; đơn mới luôn có user_id (Order bắt buộc).
-- customer_name, customer_email vẫn giữ: chụp tên, email từ hồ sơ lúc đặt, giống sku / giá của dòng đơn.
ALTER TABLE orders ADD COLUMN user_id BIGINT;

-- Danh sách đơn của một người dùng, mới nhất trước (GET /api/orders?userId=)
CREATE INDEX idx_orders_user_id_created_at ON orders (user_id, created_at DESC, id DESC);
