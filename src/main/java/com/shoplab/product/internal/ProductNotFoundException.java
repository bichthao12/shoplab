package com.shoplab.product.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

class ProductNotFoundException extends ApiException {
    ProductNotFoundException(Long id) {
        super(HttpStatus.NOT_FOUND, "product-not-found", "Product Not Found",
                "Không tìm thấy sản phẩm có id = " + id);
    }
}
