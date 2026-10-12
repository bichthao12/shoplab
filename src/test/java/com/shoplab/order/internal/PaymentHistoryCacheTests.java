package com.shoplab.order.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Cache lịch sử thanh toán theo orderId: lấy lại từ cache, và không lớn quá maximumSize dù xem bao nhiêu đơn. */
class PaymentHistoryCacheTests {

    private static final int MAXIMUM_SIZE = 100;

    private final PaymentHistoryCache cache = new PaymentHistoryCache(
            new OrderDetailProperties(Duration.ZERO, Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(1),
                    MAXIMUM_SIZE, Duration.ofMinutes(1)),
            new SimpleMeterRegistry());

    @Test
    @DisplayName("Cùng một đơn lần hai: lấy từ cache, không gọi lại cổng thanh toán")
    void secondCall_isServedFromCache() {
        AtomicInteger loads = new AtomicInteger();

        cache.get(1L, () -> events(1L, loads));
        List<PaymentEvent> second = cache.get(1L, () -> events(1L, loads));

        assertThat(loads).hasValue(1);
        assertThat(second).hasSize(2);
    }

    @Test
    @DisplayName("Xem 10.000 đơn khác nhau: cache vẫn chỉ giữ tối đa maximumSize (100) đơn")
    void manyOrders_staysWithinMaximumSize() {
        AtomicInteger loads = new AtomicInteger();

        for (long orderId = 1; orderId <= 10_000; orderId++) {
            long id = orderId;
            cache.get(id, () -> events(id, loads));
        }

        assertThat(loads).hasValue(10_000);
        assertThat(cache.size()).as("Map không giới hạn sẽ giữ cả 10.000 đơn").isLessThanOrEqualTo(MAXIMUM_SIZE);
    }

    private static List<PaymentEvent> events(long orderId, AtomicInteger loads) {
        loads.incrementAndGet();
        return List.of(new PaymentEvent("PAY-" + orderId + "-1", "CREATED", Instant.now()),
                new PaymentEvent("PAY-" + orderId + "-2", "AUTHORIZED", Instant.now()));
    }
}
