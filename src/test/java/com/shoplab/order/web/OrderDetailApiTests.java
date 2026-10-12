package com.shoplab.order.web;

import com.shoplab.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/orders/{id}/detail: 4 nguồn chạy song song (OrderDetailService). Hai nguồn giả lập chậm 300 ms (lịch sử
 * thanh toán) và 400 ms (gợi ý), cấu hình mặc định. Bản gọi lần lượt: scripts/bugs/15-order-detail-sequential.patch.
 * Nguồn phụ quá hạn: OrderDetailTimeoutTests.
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderDetailApiTests extends IntegrationTestBase {

    private static final Pattern TIMING = Pattern.compile("(\\w+);dur=([0-9.]+)");

    @Test
    @DisplayName("Đủ 4 nguồn; cả trang mất khoảng thời gian của nguồn chậm nhất, không phải tổng các nguồn")
    void detail_loadsFourSourcesInParallel() {
        long productId = createProduct("DETAIL-001", 100_000, 10);
        long orderId = idOf(json(postOrder(newKey(), orderJson(productId, 2))));
        // Giá đổi sau khi đặt: đơn giữ giá lúc đặt, phần products là giá hiện tại
        assertThat(send("PATCH", "/api/products/" + productId, """
                {"price":120000,"version":0}
                """, null).statusCode()).isEqualTo(200);
        send("GET", "/api/orders/" + orderId + "/detail", null, null);   // chạy trước một lần (nạp class, JIT)

        HttpResponse<String> r = send("GET", "/api/orders/" + orderId + "/detail", null, null);

        assertThat(r.statusCode()).isEqualTo(200);
        Map<String, Object> body = json(r);
        assertThat(firstOf(map(body.get("order")).get("items")).get("unitPrice").toString()).isEqualTo("100000.0");
        Map<String, Object> product = firstOf(body.get("products"));
        assertThat(((Number) product.get("id")).longValue()).isEqualTo(productId);
        assertThat(new BigDecimal(product.get("price").toString())).isEqualByComparingTo("120000");
        assertThat(((Number) product.get("stock")).intValue()).isEqualTo(8);
        assertThat((List<?>) body.get("paymentHistory")).hasSize(2);
        assertThat((List<?>) body.get("recommendations")).hasSize(3);
        assertThat((List<?>) body.get("unavailable")).isEmpty();

        Map<String, Double> timing = serverTiming(r);
        assertThat(timing).containsOnlyKeys("order", "products", "paymentHistory", "recommendations", "total");
        double total = timing.get("total");
        double slowest = Math.max(timing.get("paymentHistory"), timing.get("recommendations"));
        double sum = timing.get("order") + timing.get("products") + timing.get("paymentHistory") + timing.get("recommendations");
        // Song song: total ≈ nguồn chậm nhất (≈ 400 ms). Gọi lần lượt: total ≥ tổng các nguồn (≈ 700 ms)
        assertThat(total).as("total %s ms, các nguồn %s", total, timing).isGreaterThanOrEqualTo(slowest);
        assertThat(total).as("total %s ms, các nguồn %s", total, timing).isLessThan(sum - 200);
    }

    @Test
    @DisplayName("Đơn không tồn tại → 404 ngay, không chờ hai nguồn chậm; hai nguồn đó bị huỷ")
    void detail_unknownOrder_returns404WithoutWaitingForSlowSources(CapturedOutput output) {
        send("GET", "/api/orders/999999998/detail", null, null);   // chạy trước một lần (nạp class, JIT)
        long started = System.nanoTime();

        HttpResponse<String> r = send("GET", "/api/orders/999999999/detail", null, null);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertProblem(r, 404, "order-not-found");
        assertThat(elapsedMs).as("thời gian trả 404 (nguồn nhanh nhất trong hai nguồn chậm: 300 ms)").isLessThan(250);
        awaitLogged(output, "Lịch sử thanh toán (giả lập): bị huỷ", "Gợi ý (giả lập): bị huỷ");
    }

    /** Chờ tối đa 2 giây tới khi log có đủ các dòng (luồng của nguồn bị huỷ ghi log sau khi request đã trả về). */
    static void awaitLogged(CapturedOutput output, String... texts) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline && !List.of(texts).stream().allMatch(output.getOut()::contains)) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(output.getOut()).contains(texts);
    }

    static Map<String, Double> serverTiming(HttpResponse<String> r) {
        Map<String, Double> timing = new LinkedHashMap<>();
        Matcher m = TIMING.matcher(r.headers().firstValue("Server-Timing").orElse(""));
        while (m.find()) {
            timing.put(m.group(1), Double.parseDouble(m.group(2)));
        }
        return timing;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstOf(Object list) {
        return ((List<Map<String, Object>>) list).getFirst();
    }
}
