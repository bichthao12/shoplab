package com.shoplab.order.web;

import com.shoplab.order.internal.Order;
import com.shoplab.order.internal.OrderItem;
import com.shoplab.order.internal.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String customerName,
        String customerEmail,
        OrderStatus status,
        BigDecimal totalAmount,
        List<Line> items,
        Instant createdAt
) {
    public record Line(
            Long productId,
            String sku,
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {
        static Line from(OrderItem i) {
            return new Line(i.getProductId(), i.getSku(), i.getProductName(),
                    i.getQuantity(), i.getUnitPrice(), i.getLineTotal());
        }
    }

    public static OrderResponse from(Order o) {
        return new OrderResponse(
                o.getId(), o.getCustomerName(), o.getCustomerEmail(), o.getStatus(), o.getTotalAmount(),
                o.getItems().stream().map(Line::from).toList(),
                o.getCreatedAt());
    }
}
