package com.shoplab.order.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * Cache lịch sử thanh toán theo orderId: cổng thanh toán (giả lập) chậm 300 ms, trang chi tiết của cùng một đơn mở lại
 * trong thời gian sống của mục thì lấy từ cache. Caffeine, có giới hạn:
 *  - maximumSize (shoplab.order-detail.payment-cache-maximum-size, 10.000 đơn): đầy thì bỏ bớt mục ít được dùng
 *    (W-TinyLFU), nên bộ nhớ của cache có trần dù có bao nhiêu đơn được xem;
 *  - expireAfterWrite (payment-cache-ttl, 1 phút): lịch sử thanh toán thay đổi (thanh toán xong, hoàn tiền), không giữ
 *    bản cũ mãi.
 * Bản lỗi (scripts/bugs/19-payment-cache-unbounded.patch): Map theo orderId không giới hạn, không hết hạn. Mỗi đơn từng
 * được xem nằm lại trong map mãi mãi, heap tăng dần theo số đơn khác nhau được xem: leak (scripts/memory-leak.ps1).
 *
 * Không dùng cache.get(orderId, loader) / computeIfAbsent: hàm nạp (300 ms) sẽ chạy bên trong khoá synchronized của
 * ConcurrentHashMap. Java 21 ghim (pin) luồng ảo đang chờ trong synchronized vào luồng hệ điều hành, vài chục request
 * cùng nạp là chiếm hết các luồng hệ điều hành của luồng ảo. Ở đây đọc, nạp ngoài khoá, rồi ghi; hai request cùng trượt
 * một đơn thì nạp hai lần, chấp nhận được.
 *
 * Số đo qua /actuator/metrics: order.payment-history.cache.size (cả hai bản), và của Caffeine (tên cache
 * orderPaymentHistory): cache.gets{result=hit|miss}, cache.evictions, cache.size.
 */
@Component
class PaymentHistoryCache {

    private final Cache<Long, List<PaymentEvent>> cache;

    PaymentHistoryCache(OrderDetailProperties properties, MeterRegistry registry) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.paymentCacheMaximumSize())
                .expireAfterWrite(properties.paymentCacheTtl())
                .recordStats()
                .build();
        CaffeineCacheMetrics.monitor(registry, cache, "orderPaymentHistory");
        Gauge.builder("order.payment-history.cache.size", this, PaymentHistoryCache::size)
                .description("Số đơn đang nằm trong cache lịch sử thanh toán")
                .register(registry);
    }

    List<PaymentEvent> get(long orderId, Supplier<List<PaymentEvent>> load) {
        List<PaymentEvent> cached = cache.getIfPresent(orderId);
        if (cached != null) {
            return cached;
        }
        List<PaymentEvent> loaded = load.get();
        cache.put(orderId, loaded);
        return loaded;
    }

    long size() {
        cache.cleanUp();   // chạy ngay các việc dọn đang chờ (bỏ mục thừa, mục hết hạn) để số đếm đúng
        return cache.estimatedSize();
    }
}
