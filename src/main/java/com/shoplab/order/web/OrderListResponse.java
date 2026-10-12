package com.shoplab.order.web;

import com.shoplab.order.internal.OrderSlice;

import java.util.List;

/**
 * Một trang của GET /api/orders.
 * @param content    các đơn, mới nhất trước
 * @param nextCursor gửi lại ở tham số cursor để lấy trang sau; null: hết danh sách
 */
public record OrderListResponse(List<OrderSummaryResponse> content, String nextCursor) {

    static OrderListResponse from(OrderSlice slice) {
        return new OrderListResponse(
                slice.orders().stream().map(OrderSummaryResponse::from).toList(),
                slice.next() == null ? null : OrderCursor.encode(slice.next()));
    }
}
