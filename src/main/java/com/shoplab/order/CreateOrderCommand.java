package com.shoplab.order;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Dữ liệu đặt hàng đã chuẩn hoá: đầu vào của OrderService, đồng thời là "dạng chuẩn" để idempotency
 * so sánh hai request. Cùng nội dung (khác thứ tự dòng, tách dòng, khác hoa/thường email) → cùng command.
 * Không phụ thuộc HTTP: tầng web tự chuyển request sang command (CreateOrderRequest.toCommand).
 *
 * @param quantities productId → tổng số lượng, sắp theo productId
 */
public record CreateOrderCommand(String customerName, String customerEmail, SortedMap<Long, Integer> quantities) {

    public CreateOrderCommand {
        customerName = Order.normalizeCustomerName(customerName);
        customerEmail = Order.normalizeEmail(customerEmail);
        quantities = Collections.unmodifiableSortedMap(new TreeMap<>(quantities));
    }

    /** Một dòng hàng như client gửi; có thể trùng productId với dòng khác. */
    public record Line(long productId, int quantity) {}

    /** Gộp các dòng trùng productId (cộng dồn số lượng). */
    public static CreateOrderCommand of(String customerName, String customerEmail, List<Line> lines) {
        SortedMap<Long, Integer> quantities = new TreeMap<>();
        for (Line line : lines) {
            quantities.merge(line.productId(), line.quantity(), Integer::sum);
        }
        return new CreateOrderCommand(customerName, customerEmail, quantities);
    }
}
