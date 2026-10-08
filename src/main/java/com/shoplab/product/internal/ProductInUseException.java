package com.shoplab.product.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Sản phẩm còn được module khác tham chiếu (vd đã có trong đơn hàng) nên không xoá được.
 * Giữ type "data-integrity" như khi còn khoá ngoại để không đổi hợp đồng API.
 */
class ProductInUseException extends ApiException {
    ProductInUseException(String sku, String referencedBy) {
        super(HttpStatus.CONFLICT, "data-integrity", "Data Integrity Violation",
                "Sản phẩm " + sku + " đã có trong " + referencedBy
                        + " nên không thể xoá, hãy chuyển sang ngừng bán (active = false)");
    }
}
