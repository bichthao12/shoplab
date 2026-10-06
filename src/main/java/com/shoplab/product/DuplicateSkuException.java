package com.shoplab.product;

public class DuplicateSkuException extends RuntimeException {
    public DuplicateSkuException(String sku) {
        super("SKU '" + sku + "' đã tồn tại");
    }
}
