package com.shoplab.product.web;

/** Số lượt xem của một sản phẩm, từ lúc app khởi động (đếm trong bộ nhớ). */
public record ProductViewsResponse(long productId, long views) {}
