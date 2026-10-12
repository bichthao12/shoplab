package com.shoplab.order.internal;

import com.shoplab.IntegrationTestBase;
import com.shoplab.product.ProductCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * GET /api/orders/{id}/detail khi một nguồn hỏng: nguồn gợi ý lỗi, nguồn gợi ý quá hạn, nguồn sản phẩm (bắt buộc) quá
 * hạn. Mỗi lời gọi có orTimeout riêng, ở đây rút xuống 600 ms để test chạy nhanh. Nguồn gợi ý và sản phẩm là mock;
 * lịch sử thanh toán vẫn là bản giả lập 300 ms.
 */
@TestPropertySource(properties = {
        "shoplab.order-detail.required-timeout=600ms",
        "shoplab.order-detail.optional-timeout=600ms"
})
class OrderDetailDegradedTests extends IntegrationTestBase {

    @MockitoBean RecommendationSource recommendations;
    @MockitoBean ProductCatalog catalog;   // mặc định trả danh sách rỗng

    @Test
    @DisplayName("Nguồn gợi ý lỗi → vẫn 200, recommendations null, unavailable = [recommendations], các phần khác đủ")
    void recommendationsFail_pageStillReturnsWithoutThem() {
        long orderId = newOrder("DEGRADED-1");
        when(recommendations.forOrder(anyLong())).thenThrow(new IllegalStateException("dịch vụ gợi ý trả 500"));
        long started = System.nanoTime();

        HttpResponse<String> r = send("GET", "/api/orders/" + orderId + "/detail", null, null);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(r.statusCode()).isEqualTo(200);
        Map<String, Object> body = json(r);
        assertThat(body.get("recommendations")).isNull();
        assertThat(body.get("unavailable")).isEqualTo(List.of("recommendations"));
        assertThat((List<?>) body.get("paymentHistory")).hasSize(2);
        assertThat(map(body.get("order")).get("id")).isNotNull();
        // không chờ tới hạn 600 ms: lỗi được biết ngay, trang chỉ còn chờ lịch sử thanh toán (300 ms)
        assertThat(elapsedMs).isLessThan(550);
    }

    @Test
    @DisplayName("Nguồn gợi ý quá hạn (orTimeout 600 ms) → vẫn 200 sau khoảng 600 ms, không có gợi ý; lời gọi gợi ý bị ngắt")
    void recommendationsTooSlow_cutOffAtTimeoutAndInterrupted() throws InterruptedException {
        long orderId = newOrder("DEGRADED-2");
        CountDownLatch interrupted = new CountDownLatch(1);
        when(recommendations.forOrder(anyLong())).thenAnswer(call -> sleepUntilInterrupted(interrupted));
        long started = System.nanoTime();

        HttpResponse<String> r = send("GET", "/api/orders/" + orderId + "/detail", null, null);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(r.statusCode()).isEqualTo(200);
        Map<String, Object> body = json(r);
        assertThat(body.get("recommendations")).isNull();
        assertThat(body.get("unavailable")).isEqualTo(List.of("recommendations"));
        assertThat((List<?>) body.get("paymentHistory")).hasSize(2);
        assertThat(elapsedMs).as("hạn của lời gọi gợi ý 600 ms, mock chờ 10 giây").isBetween(550L, 1500L);
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).as("luồng đang chạy lời gọi gợi ý bị ngắt").isTrue();
    }

    @Test
    @DisplayName("Nguồn sản phẩm (bắt buộc) quá hạn → 503 order-detail-timeout + Retry-After ngay khi hết hạn; lời gọi không bị ngắt")
    void productsTooSlow_returns503() throws Exception {
        long orderId = newOrder("DEGRADED-3");
        CompletableFuture<String> call = new CompletableFuture<>();
        when(catalog.findByIds(any())).thenAnswer(invocation -> {
            try {
                Thread.sleep(1_500);
                call.complete("chạy hết");
            } catch (InterruptedException ex) {
                call.complete("bị ngắt");
            }
            return List.of();
        });
        long started = System.nanoTime();

        HttpResponse<String> r = send("GET", "/api/orders/" + orderId + "/detail", null, null);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertProblem(r, 503, "order-detail-timeout");
        assertThat(r.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(elapsedMs).as("hạn của lời gọi sản phẩm 600 ms, mock chờ 1,5 giây").isBetween(550L, 1400L);
        assertThat(call.get(5, TimeUnit.SECONDS)).as("lời gọi bắt buộc quá hạn chỉ bị bỏ kết quả").isEqualTo("chạy hết");
    }

    @Test
    @DisplayName("Sản phẩm quá hạn giữa câu SQL → 503, nhưng câu SQL không bị ngắt: chạy xong, kết nối DB không bị đóng")
    void productsQueryTooSlow_returns503WithoutBreakingConnection() throws Exception {
        long orderId = newOrder("DEGRADED-4");
        CompletableFuture<String> query = new CompletableFuture<>();
        when(catalog.findByIds(any())).thenAnswer(call -> {
            try {
                // câu SQL thật, chậm 1 giây: quá hạn 600 ms khi đang chờ PostgreSQL trả lời
                jdbc.sql("SELECT 1 FROM pg_sleep(1)").query(Integer.class).single();
                query.complete("xong");
            } catch (RuntimeException ex) {
                query.complete("lỗi: " + ex.getMessage());
            }
            return List.of();
        });

        HttpResponse<String> r = send("GET", "/api/orders/" + orderId + "/detail", null, null);

        assertProblem(r, 503, "order-detail-timeout");
        // Ngắt luồng ảo đang đọc socket thì JDK đóng luôn socket: driver báo "An I/O error occurred while sending to
        // the backend", kết nối bị bỏ khỏi pool. Lời gọi DB quá hạn vì vậy không bị ngắt, chỉ bị bỏ kết quả.
        assertThat(query.get(5, TimeUnit.SECONDS)).isEqualTo("xong");
    }

    private long newOrder(String sku) {
        long productId = createProduct(sku, 100_000, 10);
        return idOf(json(postOrder(newKey(), orderJson(productId, 1))));
    }

    /** Giả một nguồn treo 10 giây; bị ngắt thì báo qua latch rồi dừng. */
    private static List<Object> sleepUntilInterrupted(CountDownLatch interrupted) {
        try {
            Thread.sleep(10_000);
        } catch (InterruptedException ex) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
