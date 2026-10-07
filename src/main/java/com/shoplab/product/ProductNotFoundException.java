package com.shoplab.product;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

public class ProductNotFoundException extends ApiException {
    public ProductNotFoundException(Long id) {
        super(HttpStatus.NOT_FOUND, "product-not-found", "Product Not Found",
                "Không tìm thấy sản phẩm có id = " + id);
    }
}
