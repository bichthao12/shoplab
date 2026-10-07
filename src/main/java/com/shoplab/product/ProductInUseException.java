package com.shoplab.product;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Sản phẩm đã nằm trong đơn hàng nên không xoá được (DB chặn bằng fk_order_items_product).
 * Giữ type "data-integrity" như trước để không đổi hợp đồng API, chỉ thêm câu báo rõ ràng.
 */
public class ProductInUseException extends ApiException {
    public ProductInUseException(String sku) {
        super(HttpStatus.CONFLICT, "data-integrity", "Data Integrity Violation",
                "Sản phẩm " + sku + " đã có trong đơn hàng nên không thể xoá, hãy chuyển sang ngừng bán (active = false)");
    }
}
