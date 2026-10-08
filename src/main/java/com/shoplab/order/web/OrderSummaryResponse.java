package com.shoplab.order.web;

import com.shoplab.order.internal.Order;
import com.shoplab.order.internal.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** Một đơn trong danh sách: không kèm dòng hàng, nên không phải nạp order_items cho cả trang. */
public record OrderSummaryResponse(
        Long id,
        Long userId,
        OrderStatus status,
        BigDecimal totalAmount,
        Instant createdAt
) {
    public static OrderSummaryResponse from(Order o) {
        return new OrderSummaryResponse(o.getId(), o.getUserId(), o.getStatus(), o.getTotalAmount(), o.getCreatedAt());
    }
}
