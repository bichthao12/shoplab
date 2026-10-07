-- Chụp lại SKU và tên sản phẩm vào từng dòng đơn tại thời điểm đặt hàng (giống unit_price),
-- để sửa sản phẩm sau đó không làm đổi các đơn cũ. product_id và FK vẫn giữ nguyên.
ALTER TABLE order_items
    ADD COLUMN sku          VARCHAR(64),
    ADD COLUMN product_name VARCHAR(255);

-- Đơn đã có: lấy theo sản phẩm hiện tại (giá trị tốt nhất còn lại)
UPDATE order_items oi
SET sku          = p.sku,
    product_name = p.name
FROM products p
WHERE p.id = oi.product_id;

ALTER TABLE order_items
    ALTER COLUMN sku          SET NOT NULL,
    ALTER COLUMN product_name SET NOT NULL;
