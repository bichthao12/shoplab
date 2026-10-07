package com.shoplab.order;

import com.shoplab.order.dto.CreateOrderRequest;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Dữ liệu đặt hàng đã chuẩn hoá: đầu vào của OrderService, đồng thời là "dạng chuẩn" để idempotency
 * so sánh hai request. Cùng nội dung (khác thứ tự dòng, tách dòng, khác hoa/thường email) → cùng command.
 *
 * @param quantities productId → tổng số lượng, sắp theo productId
 */
public record CreateOrderCommand(String customerName, String customerEmail, SortedMap<Long, Integer> quantities) {

    public CreateOrderCommand {
        customerName = Order.normalizeCustomerName(customerName);
        customerEmail = Order.normalizeEmail(customerEmail);
        quantities = Collections.unmodifiableSortedMap(new TreeMap<>(quantities));
    }

    /** Từ request đã qua validation: gộp các dòng trùng productId (cộng dồn số lượng). */
    public static CreateOrderCommand from(CreateOrderRequest req) {
        SortedMap<Long, Integer> quantities = new TreeMap<>();
        for (CreateOrderRequest.Item item : req.items()) {
            quantities.merge(item.productId(), item.quantity(), Integer::sum);
        }
        return new CreateOrderCommand(req.customerName(), req.customerEmail(), quantities);
    }
}
