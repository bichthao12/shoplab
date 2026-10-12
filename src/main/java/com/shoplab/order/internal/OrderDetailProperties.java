package com.shoplab.order.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cấu hình trang chi tiết đơn (GET /api/orders/{id}/detail), tiền tố shoplab.order-detail.
 *
 * @param paymentHistoryDelay  độ trễ giả lập của nguồn lịch sử thanh toán
 * @param recommendationsDelay độ trễ giả lập của nguồn gợi ý
 * @param requiredTimeout      hạn (orTimeout) của mỗi lời gọi bắt buộc: đơn, sản phẩm. Quá hạn → trang trả 503
 * @param optionalTimeout      hạn (orTimeout) của mỗi lời gọi không bắt buộc: lịch sử thanh toán, gợi ý. Quá hạn thì
 *                             trang trả về không có phần đó (ghi vào unavailable) thay vì chờ tiếp
 */
@ConfigurationProperties("shoplab.order-detail")
record OrderDetailProperties(
        @DefaultValue("300ms") Duration paymentHistoryDelay,
        @DefaultValue("400ms") Duration recommendationsDelay,
        @DefaultValue("2s") Duration requiredTimeout,
        @DefaultValue("1s") Duration optionalTimeout
) {}
