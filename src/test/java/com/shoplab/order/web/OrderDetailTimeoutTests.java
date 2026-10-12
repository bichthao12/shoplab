package com.shoplab.order.web;

import com.shoplab.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/orders/{id}/detail khi nguồn gợi ý chậm quá hạn: gợi ý 3 giây, hạn chờ nguồn phụ 600 ms. Trang vẫn trả
 * về sau khoảng 600 ms, không có phần gợi ý, và nguồn gợi ý bị huỷ (không chạy tiếp 3 giây vô ích).
 * Cấu hình riêng nên lớp này dùng Spring context riêng.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "shoplab.order-detail.recommendations-delay=3s",
        "shoplab.order-detail.optional-timeout=600ms"
})
class OrderDetailTimeoutTests extends IntegrationTestBase {

    @Test
    @DisplayName("Gợi ý quá hạn → vẫn 200, recommendations null, unavailable = [recommendations], nguồn gợi ý bị huỷ")
    void slowRecommendations_pageStillReturnsWithoutThem(CapturedOutput output) {
        long productId = createProduct("DETAIL-TIMEOUT", 100_000, 10);
        long orderId = idOf(json(postOrder(newKey(), orderJson(productId, 1))));
        long started = System.nanoTime();

        HttpResponse<String> r = send("GET", "/api/orders/" + orderId + "/detail", null, null);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(r.statusCode()).isEqualTo(200);
        Map<String, Object> body = json(r);
        assertThat(body.get("recommendations")).isNull();
        assertThat(body.get("unavailable")).isEqualTo(List.of("recommendations"));
        assertThat((List<?>) body.get("paymentHistory")).hasSize(2);
        assertThat(elapsedMs).as("thời gian trang (hạn 600 ms, nguồn gợi ý 3 giây)").isBetween(550L, 1500L);
        OrderDetailApiTests.awaitLogged(output, "Gợi ý (giả lập): bị huỷ");
    }
}
