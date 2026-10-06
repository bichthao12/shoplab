package com.shoplab.product.dto;

import com.shoplab.product.Product;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        String category,
        BigDecimal price,
        int stock,
        boolean active,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getCategory(),
                p.getPrice(), p.getStock(), p.isActive(), p.getVersion(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
