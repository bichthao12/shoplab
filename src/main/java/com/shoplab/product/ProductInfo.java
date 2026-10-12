package com.shoplab.product;

import java.math.BigDecimal;

/** Thông tin hiện tại của một sản phẩm (ProductCatalog). */
public record ProductInfo(
        Long id,
        String sku,
        String name,
        BigDecimal price,
        int stock,
        boolean active
) {}
