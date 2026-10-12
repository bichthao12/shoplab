package com.shoplab.order.internal;

/** Một sản phẩm gợi ý kèm lý do (nguồn gợi ý). */
public record Recommendation(String sku, String name, String reason) {}
