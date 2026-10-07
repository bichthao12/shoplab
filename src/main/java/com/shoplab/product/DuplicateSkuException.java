package com.shoplab.product;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

public class DuplicateSkuException extends ApiException {
    public DuplicateSkuException(String sku) {
        super(HttpStatus.CONFLICT, "duplicate-sku", "Duplicate SKU", "SKU '" + sku + "' đã tồn tại");
    }
}
