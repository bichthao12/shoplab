package com.shoplab.product;

import java.math.BigDecimal;

/**
 * Một dòng hàng đã được giữ (đã trừ kho), kèm thông tin sản phẩm TẠI THỜI ĐIỂM giữ hàng
 * để module order chụp lại vào đơn.
 */
public record ReservedItem(
        Long productId,
        String sku,
        String name,
        BigDecimal unitPrice,
        int quantity
) {}
