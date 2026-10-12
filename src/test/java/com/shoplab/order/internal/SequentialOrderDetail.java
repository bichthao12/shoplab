package com.shoplab.order.internal;

import com.shoplab.product.ProductCatalog;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Bản GỌI LẦN LƯỢT 4 nguồn của trang chi tiết đơn, chỉ có trong test: số "trước" của sổ đo (OrderDetailTimingTests).
 * Cùng 4 nguồn với OrderDetailService, nhưng nguồn sau chờ nguồn trước xong, trên luồng của bên gọi.
 * Bản lỗi tương ứng của app (cho red-green.ps1): scripts/bugs/15-order-detail-sequential.patch.
 */
@Component
class SequentialOrderDetail {

    private final OrderService orders;
    private final ProductCatalog catalog;
    private final PaymentHistorySource paymentHistory;
    private final RecommendationSource recommendations;

    SequentialOrderDetail(OrderService orders, ProductCatalog catalog, PaymentHistorySource paymentHistory,
                          RecommendationSource recommendations) {
        this.orders = orders;
        this.catalog = catalog;
        this.paymentHistory = paymentHistory;
        this.recommendations = recommendations;
    }

    /** @return thời gian gom đủ 4 nguồn */
    Duration load(long orderId) {
        long started = System.nanoTime();
        Order order = orders.getById(orderId);
        catalog.findByIds(order.getItems().stream().map(OrderItem::getProductId).distinct().toList());
        paymentHistory.findByOrderId(orderId);
        recommendations.forOrder(orderId);
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
