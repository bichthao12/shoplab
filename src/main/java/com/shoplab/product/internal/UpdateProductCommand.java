package com.shoplab.product.internal;

import java.math.BigDecimal;

/**
 * Thay đổi một phần sản phẩm: trường nào null thì giữ nguyên. Đầu vào của ProductService, không phụ thuộc HTTP.
 *
 * @param expectedVersion version của sản phẩm mà client đã đọc; khác version hiện tại thì không sửa
 */
public record UpdateProductCommand(
        String sku,
        String name,
        String description,
        String category,
        BigDecimal price,
        Integer stock,
        Boolean active,
        long expectedVersion
) {}
