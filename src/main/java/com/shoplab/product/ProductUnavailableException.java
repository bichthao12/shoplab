package com.shoplab.product;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Sản phẩm không tồn tại hoặc đang ngừng bán nên không giữ hàng được.
 * API đặt hàng dịch lỗi này thành invalid-order (xem OrderService).
 */
public class ProductUnavailableException extends ApiException {
    public ProductUnavailableException(String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "product-unavailable", "Product Unavailable", detail);
    }
}
