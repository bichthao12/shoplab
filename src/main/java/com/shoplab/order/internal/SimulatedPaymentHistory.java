package com.shoplab.order.internal;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Lịch sử thanh toán GIẢ LẬP: chờ shoplab.order-detail.payment-history-delay (mặc định 300 ms) như khi gọi cổng thanh
 * toán, rồi trả hai sự kiện dựng từ id đơn. Không đọc DB.
 */
@Component
class SimulatedPaymentHistory implements PaymentHistorySource {

    private final OrderDetailProperties properties;

    SimulatedPaymentHistory(OrderDetailProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<PaymentEvent> findByOrderId(long orderId) {
        SimulatedDelay.sleep(properties.paymentHistoryDelay(), "Lịch sử thanh toán (giả lập)");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return List.of(
                new PaymentEvent("PAY-" + orderId + "-1", "CREATED", now.minus(10, ChronoUnit.MINUTES)),
                new PaymentEvent("PAY-" + orderId + "-2", "AUTHORIZED", now.minus(9, ChronoUnit.MINUTES)));
    }
}
