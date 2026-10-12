package com.shoplab.order.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/** Độ trễ của nguồn giả lập. Bị ngắt (trang không cần kết quả nữa) thì dừng ngay, không chờ hết. */
final class SimulatedDelay {

    private static final Logger log = LoggerFactory.getLogger(SimulatedDelay.class);

    private SimulatedDelay() {
    }

    static void sleep(Duration delay, String source) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("{}: bị huỷ, trang chi tiết đơn không cần kết quả nữa", source);
            throw new IllegalStateException(source + " bị huỷ", ex);
        }
    }
}
