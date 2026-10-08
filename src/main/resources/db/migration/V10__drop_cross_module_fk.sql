-- Bỏ khoá ngoại nối bảng của hai module khác nhau (order_items thuộc module order, products thuộc module product).
-- Mỗi module tự giữ toàn vẹn dữ liệu của mình: dòng đơn đã chụp sku, tên, giá nên không cần đọc products;
-- còn việc chặn xoá sản phẩm đã có trong đơn chuyển lên code (module product hỏi qua API ProductReferences).
-- Giữ index idx_order_items_product_id: câu kiểm tra "sản phẩm còn trong đơn nào không" dùng nó.
ALTER TABLE order_items DROP CONSTRAINT fk_order_items_product;
