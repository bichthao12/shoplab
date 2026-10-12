package com.shoplab.order.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/** Trang chi tiết đơn: đơn hoặc sản phẩm không tải kịp (quá shoplab.order-detail.required-timeout) → 503, thử lại sau. */
class OrderDetailTimeoutException extends ApiException {
    OrderDetailTimeoutException(Duration timeout) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "order-detail-timeout", "Order Detail Timeout",
                "Không tải được đơn hàng hoặc sản phẩm trong " + timeout.toMillis() + " ms, hãy thử lại sau");
        addHeader(HttpHeaders.RETRY_AFTER, "1");
    }
}
