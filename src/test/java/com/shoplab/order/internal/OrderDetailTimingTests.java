package com.shoplab.order.internal;

import com.shoplab.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trang chi tiết đơn, gọi lần lượt (SequentialOrderDetail, chỉ có trong test) so với gọi song song (OrderDetailService
 * của app), cùng 4 nguồn: lịch sử thanh toán giả lập 300 ms, gợi ý 400 ms. Mỗi cách chạy 3 lần sau một lần chạy trước,
 * như sổ đo; dòng "[so-do] order-detail ..." cho scripts/before-after.ps1 -Case order-detail đọc.
 */
class OrderDetailTimingTests extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(OrderDetailTimingTests.class);

    @Autowired SequentialOrderDetail sequential;
    @Autowired OrderDetailService parallel;

    @Test
    @DisplayName("Gọi song song mất khoảng thời gian nguồn chậm nhất (400 ms), gọi lần lượt mất tổng các nguồn (700 ms)")
    void parallelTakesSlowestSource_sequentialTakesTheSum() {
        long productId = createProduct("TIMING-1", 100_000, 10);
        long orderId = idOf(json(postOrder(newKey(), orderJson(productId, 2))));
        sequential.load(orderId);   // chạy trước một lần (nạp class, JIT)
        parallel.load(orderId);

        double sequentialMs = 0;
        double parallelMs = 0;
        for (int run = 1; run <= 3; run++) {
            sequentialMs = sequential.load(orderId).toNanos() / 1_000_000.0;
            parallelMs = parallel.load(orderId).timings().get("total").toNanos() / 1_000_000.0;
            log.info("[order-detail] lần {}: lần lượt {} ms, song song {} ms", run, String.format(Locale.ROOT, "%.1f", sequentialMs),
                    String.format(Locale.ROOT, "%.1f", parallelMs));
        }
        // Dòng cho scripts/before-after.ps1 đọc (chỉ ký tự ASCII): lần đo cuối
        log.info(String.format(Locale.ROOT, "[so-do] order-detail sequential=%.1f parallel=%.1f", sequentialMs, parallelMs));

        assertThat(sequentialMs).as("gọi lần lượt: tổng 300 + 400 ms và đơn, sản phẩm").isGreaterThanOrEqualTo(700);
        assertThat(parallelMs).as("gọi song song: ít nhất bằng nguồn chậm nhất (400 ms)").isGreaterThanOrEqualTo(400);
        assertThat(parallelMs).as("gọi song song: nhanh hơn gọi lần lượt").isLessThan(sequentialMs - 200);
    }
}
