package com.shoplab;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test cho ranh giới giữa module product và order:
 *  - đơn hàng chụp lại thông tin sản phẩm tại thời điểm đặt,
 *  - sản phẩm không bán được khi đặt hàng vẫn trả 422 invalid-order như trước,
 *  - lỗi ràng buộc DB được service sở hữu dữ liệu dịch sang lỗi có nghĩa.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductOrderIntegrationTests {

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE TABLE order_items, orders, products, idempotency_keys RESTART IDENTITY CASCADE")
                .update();
    }

    // =====================================================================
    // 1. Đơn hàng chụp lại thông tin sản phẩm
    // =====================================================================

    @Test
    @DisplayName("Sửa SKU, tên, giá sản phẩm sau khi đặt → đơn cũ vẫn giữ thông tin lúc đặt")
    void orderKeepsProductSnapshot_afterProductIsEdited() {
        long productId = createProduct("SNAP-001", 100_000, 10, true);
        HttpResponse<String> created = send("POST", "/api/orders", orderJson(productId, 2), newKey());
        assertThat(created.statusCode()).isEqualTo(201);

        HttpResponse<String> patched = send("PATCH", "/api/products/" + productId, """
                {"sku":"SNAP-001-NEW","name":"Tên mới","price":1}
                """, null);
        assertThat(patched.statusCode()).isEqualTo(200);

        HttpResponse<String> r = send("GET", "/api/orders/" + idOf(json(created)), null, null);

        assertThat(r.statusCode()).isEqualTo(200);
        Map<String, Object> line = firstItem(json(r));
        assertThat(((Number) line.get("productId")).longValue()).isEqualTo(productId);
        assertThat(line.get("sku")).isEqualTo("SNAP-001");
        assertThat(line.get("productName")).isEqualTo("Sản phẩm SNAP-001");
        assertThat(new BigDecimal(line.get("unitPrice").toString())).isEqualByComparingTo("100000");
    }

    // =====================================================================
    // 2. Sản phẩm không bán được → 422 invalid-order
    // =====================================================================

    @Test
    @DisplayName("Đơn có sản phẩm không tồn tại → 422 invalid-order, kho sản phẩm khác không bị trừ")
    void orderWithUnknownProduct_returns422() {
        long productId = createProduct("UNAV-001", 100_000, 10, true);
        String body = """
                {"customerName":"Nguyễn Văn A","customerEmail":"a.nguyen@example.com",
                 "items":[{"productId":%d,"quantity":2},{"productId":999999,"quantity":1}]}
                """.formatted(productId);

        HttpResponse<String> r = send("POST", "/api/orders", body, newKey());

        assertProblem(r, 422, "invalid-order");
        assertThat(json(r).get("detail").toString()).contains("999999");
        assertThat(stockOf(productId)).isEqualTo(10);
        assertThat(count("orders")).isZero();
    }

    @Test
    @DisplayName("Đơn có sản phẩm đang ngừng bán → 422 invalid-order")
    void orderWithInactiveProduct_returns422() {
        long productId = createProduct("UNAV-002", 100_000, 10, false);

        HttpResponse<String> r = send("POST", "/api/orders", orderJson(productId, 1), newKey());

        assertProblem(r, 422, "invalid-order");
        assertThat(json(r).get("detail").toString()).contains("UNAV-002");
        assertThat(stockOf(productId)).isEqualTo(10);
    }

    // =====================================================================
    // 3. Lỗi ràng buộc DB được dịch sang lỗi có nghĩa
    // =====================================================================

    @Test
    @DisplayName("Xoá sản phẩm đã có trong đơn → 409 data-integrity kèm lý do rõ ràng, sản phẩm vẫn còn")
    void deleteProductInOrder_returns409WithReason() {
        long productId = createProduct("DEL-001", 100_000, 10, true);
        assertThat(send("POST", "/api/orders", orderJson(productId, 1), newKey()).statusCode()).isEqualTo(201);

        HttpResponse<String> r = send("DELETE", "/api/products/" + productId, null, null);

        assertProblem(r, 409, "data-integrity");
        assertThat(json(r).get("detail").toString()).contains("DEL-001").contains("đơn hàng");
        assertThat(count("products")).isEqualTo(1);
    }

    @Test
    @DisplayName("SKU trùng lọt qua bước kiểm tra trước (2 request cùng lúc) → DB chặn, vẫn trả 409 duplicate-sku")
    void duplicateSkuCaughtByDatabase_returnsDuplicateSku() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement("""
                    INSERT INTO products (sku, name, category, price, stock)
                    VALUES ('RACE-001', 'Bản đến trước', 'test', 1, 1)
                    """)) {
                ps.executeUpdate();     // chưa commit: request bên dưới không thấy dòng này ở bước existsBySku
            }

            CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(() ->
                    send("POST", "/api/products", """
                            {"sku":"RACE-001","name":"Bản đến sau","category":"test","price":1,"stock":1}
                            """, null));
            awaitSessionWaitingForLock();   // request đã tới INSERT và đang chờ transaction kia
            other.commit();

            assertProblem(pending.get(30, TimeUnit.SECONDS), 409, "duplicate-sku");
        }
        assertThat(count("products")).isEqualTo(1);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private HttpResponse<String> send(String method, String path, String jsonBody, String idempotencyKey) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null) {
            req.header("Content-Type", "application/json");
        }
        if (idempotencyKey != null) {
            req.header("Idempotency-Key", idempotencyKey);
        }
        try {
            return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Chờ tới khi có một session trong DB đang chờ khoá (tối đa 10 giây). */
    private void awaitSessionWaitingForLock() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            long waiting = jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")
                    .query(Long.class).single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Request không dừng ở bước INSERT như mong đợi");
    }

    private long createProduct(String sku, long price, int stock, boolean active) {
        return jdbc.sql("""
                        INSERT INTO products (sku, name, category, price, stock, active)
                        VALUES (:sku, :name, 'test', :price, :stock, :active)
                        RETURNING id
                        """)
                .param("sku", sku)
                .param("name", "Sản phẩm " + sku)
                .param("price", BigDecimal.valueOf(price))
                .param("stock", stock)
                .param("active", active)
                .query(Long.class)
                .single();
    }

    private int stockOf(long productId) {
        return jdbc.sql("SELECT stock FROM products WHERE id = :id")
                .param("id", productId).query(Integer.class).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static String orderJson(long productId, int quantity) {
        return """
                {"customerName":"Nguyễn Văn A","customerEmail":"a.nguyen@example.com",
                 "items":[{"productId":%d,"quantity":%d}]}
                """.formatted(productId, quantity);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(HttpResponse<String> r) {
        return mapper.readValue(r.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstItem(Map<String, Object> order) {
        return ((List<Map<String, Object>>) order.get("items")).getFirst();
    }

    private static long idOf(Map<String, Object> body) {
        return ((Number) body.get("id")).longValue();
    }

    private void assertProblem(HttpResponse<String> r, int status, String type) {
        assertThat(r.statusCode()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/problem+json"));
        Map<String, Object> body = json(r);
        assertThat(((Number) body.get("status")).intValue()).isEqualTo(status);
        assertThat(body.get("type")).isEqualTo("https://shoplab.dev/errors/" + type);
    }
}
