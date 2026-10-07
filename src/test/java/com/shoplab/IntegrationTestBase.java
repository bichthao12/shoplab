package com.shoplab;

import org.junit.jupiter.api.BeforeEach;
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
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nền chung cho integration test qua HTTP: app chạy thật trên cổng ngẫu nhiên, PostgreSQL thật (Testcontainers),
 * Flyway chạy toàn bộ migration. Mọi lớp con có cùng cấu hình nên dùng chung một Spring context (và một container).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    @Value("${local.server.port}")
    protected int port;

    @Autowired protected JdbcClient jdbc;
    @Autowired protected DataSource dataSource;
    @Autowired protected ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    protected void cleanDatabase() {
        jdbc.sql("TRUNCATE TABLE order_items, orders, products, idempotency_keys RESTART IDENTITY CASCADE")
                .update();
    }

    // ---------- HTTP ----------

    protected HttpResponse<String> send(String method, String path, String jsonBody, String idempotencyKey) {
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

    protected HttpResponse<String> postOrder(String idempotencyKey, String jsonBody) {
        return send("POST", "/api/orders", jsonBody, idempotencyKey);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> json(HttpResponse<String> r) {
        return mapper.readValue(r.body(), Map.class);
    }

    protected void assertProblem(HttpResponse<String> r, int status) {
        assertThat(r.statusCode()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("application/problem+json"));
        assertThat(((Number) json(r).get("status")).intValue()).isEqualTo(status);
    }

    protected void assertProblem(HttpResponse<String> r, int status, String type) {
        assertProblem(r, status);
        assertThat(json(r).get("type")).isEqualTo("https://shoplab.dev/errors/" + type);
    }

    // ---------- Dữ liệu ----------

    protected long createProduct(String sku, long price, int stock) {
        return createProduct(sku, price, stock, true);
    }

    protected long createProduct(String sku, long price, int stock, boolean active) {
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

    protected int stockOf(long productId) {
        return jdbc.sql("SELECT stock FROM products WHERE id = :id")
                .param("id", productId).query(Integer.class).single();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    protected static String orderJson(long productId, int quantity) {
        return """
                {"customerName":"Nguyễn Văn A","customerEmail":"a.nguyen@example.com",
                 "items":[{"productId":%d,"quantity":%d}]}
                """.formatted(productId, quantity);
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected static long idOf(Map<String, Object> body) {
        return ((Number) body.get("id")).longValue();
    }
}
