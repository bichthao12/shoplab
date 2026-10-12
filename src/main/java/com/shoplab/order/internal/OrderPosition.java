package com.shoplab.order.internal;

import org.springframework.util.Assert;

import java.time.Instant;

/**
 * Vị trí của một đơn trong danh sách đơn mới nhất trước, sắp theo (createdAt DESC, id DESC). Trang sau của danh sách
 * cursor bắt đầu ngay sau vị trí của đơn cuối trang trước (OrderService.listOrders).
 */
public record OrderPosition(Instant createdAt, long id) {

    public OrderPosition {
        Assert.notNull(createdAt, "createdAt không được null");
    }

    static OrderPosition of(Order order) {
        return new OrderPosition(order.getCreatedAt(), order.getId());
    }
}
