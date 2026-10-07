package com.shoplab.product.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

class DuplicateSkuException extends ApiException {
    DuplicateSkuException(String sku) {
        super(HttpStatus.CONFLICT, "duplicate-sku", "Duplicate SKU", "SKU '" + sku + "' đã tồn tại");
    }
}
