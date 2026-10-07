package com.shoplab.product.internal;

import java.math.BigDecimal;

/** Dữ liệu tạo sản phẩm: đầu vào của ProductService, không phụ thuộc HTTP. Chuẩn hoá và kiểm tra nằm ở Product. */
public record CreateProductCommand(
        String sku,
        String name,
        String description,
        String category,
        BigDecimal price,
        int stock,
        boolean active
) {}
