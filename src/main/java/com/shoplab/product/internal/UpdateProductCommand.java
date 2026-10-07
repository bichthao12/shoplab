package com.shoplab.product.internal;

import java.math.BigDecimal;

/** Thay đổi một phần sản phẩm: trường nào null thì giữ nguyên. Đầu vào của ProductService, không phụ thuộc HTTP. */
public record UpdateProductCommand(
        String sku,
        String name,
        String description,
        String category,
        BigDecimal price,
        Integer stock,
        Boolean active
) {}
