package com.shoplab.product.internal;

/**
 * Cộng (delta > 0) hoặc trừ (delta < 0) tồn kho của sản phẩm productId.
 * Đầu vào của ProductService, cũng là dạng chuẩn của request cho idempotency.
 */
public record AdjustStockCommand(long productId, int delta) {}
