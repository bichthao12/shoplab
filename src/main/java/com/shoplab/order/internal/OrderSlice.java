package com.shoplab.order.internal;

import java.util.List;

/**
 * Một trang của danh sách đơn phân trang cursor.
 * @param orders các đơn, mới nhất trước
 * @param next   vị trí để lấy trang sau (đơn cuối của trang này); null khi đây là trang cuối
 */
public record OrderSlice(List<Order> orders, OrderPosition next) {

    public OrderSlice {
        orders = List.copyOf(orders);
    }
}
