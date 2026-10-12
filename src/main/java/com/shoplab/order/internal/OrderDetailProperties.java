package com.shoplab.order.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cấu hình trang chi tiết đơn (GET /api/orders/{id}/detail), tiền tố shoplab.order-detail.
 *
 * @param paymentHistoryDelay  độ trễ giả lập của nguồn lịch sử thanh toán
 * @param recommendationsDelay độ trễ giả lập của nguồn gợi ý
 * @param optionalTimeout      nguồn phụ (lịch sử thanh toán, gợi ý) chưa xong sau chừng này, tính từ đầu request,
 *                             thì trang trả về không có phần đó (ghi vào unavailable) thay vì chờ tiếp
 */
@ConfigurationProperties("shoplab.order-detail")
record OrderDetailProperties(
        @DefaultValue("300ms") Duration paymentHistoryDelay,
        @DefaultValue("400ms") Duration recommendationsDelay,
        @DefaultValue("1s") Duration optionalTimeout
) {}
