package com.shoplab.product;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(Long id) {
        super("Không tìm thấy sản phẩm có id = " + id);
    }
}
