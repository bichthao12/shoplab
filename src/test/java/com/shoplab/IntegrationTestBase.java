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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /** Người đặt mặc định của orderJson, tạo khi cần lần đầu trong mỗi test (xem customerId()). */
    private Long customerId;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Không dùng RESTART IDENTITY: Hibernate đang giữ sẵn một dải id lấy từ sequence (allocationSize = 50),
     * reset sequence thì id cấp bằng SQL có thể trùng với dải đó.
     */
    @BeforeEach
    protected void cleanDatabase() {
        jdbc.sql("TRUNCATE TABLE order_items, orders, products, idempotency_keys, accounts, users, wallets CASCADE").update();
        customerId = null;
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

    /**
     * buyers lượt mua 1 cái cùng lúc qua POST /api/orders (Concurrently), mỗi lượt một Idempotency-Key riêng
     * nên là một lần mua khác nhau, không phải gửi lại.
     *
     * @return số response theo kết quả, vd {"201": 1, "409 insufficient-stock": 999}
     */
    protected Map<String, Long> buyAtOnce(long productId, int buyers) throws Exception {
        String body = orderJson(productId, 1);
        List<HttpResponse<String>> responses = Concurrently.run(buyers, i -> postOrder(newKey(), body));
        return responses.stream().collect(Collectors.groupingBy(this::outcome, TreeMap::new, Collectors.counting()));
    }

    /** "201", hoặc mã lỗi kèm type của ProblemDetail, vd "409 insufficient-stock". */
    private String outcome(HttpResponse<String> r) {
        if (r.statusCode() < 400) {
            return String.valueOf(r.statusCode());
        }
        String type = String.valueOf(json(r).get("type"));
        return r.statusCode() + " " + type.substring(type.lastIndexOf('/') + 1);
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

    // ---------- DB ----------

    /**
     * Chờ tới khi có một session trong DB đang chờ khoá (tối đa 10 giây).
     * Dùng để dựng tình huống 2 request cùng lúc: request đang chờ transaction khác commit.
     */
    protected void awaitSessionWaitingForLock() throws InterruptedException {
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

    /** Tạo người dùng (users + accounts) bằng SQL; status: ACTIVE, LOCKED hoặc DISABLED. */
    protected long createUser(String email, String fullName, String username, String status) {
        long userId = jdbc.sql("INSERT INTO users (email, full_name) VALUES (:email, :fullName) RETURNING id")
                .param("email", email)
                .param("fullName", fullName)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        INSERT INTO accounts (user_id, username, password_hash, status)
                        VALUES (:userId, :username, '{bcrypt}không-dùng-để-đăng-nhập', :status)
                        """)
                .param("userId", userId)
                .param("username", username)
                .param("status", status)
                .update();
        return userId;
    }

    /**
     * count đơn PENDING của userId, mỗi đơn một dòng hàng cho mỗi sản phẩm đang có (tạo sản phẩm trước); đơn sau mới
     * hơn đơn trước 1 phút. Tạo bằng SQL cho nhanh (100 đơn qua API mất vài giây).
     */
    protected void createOrdersWithItems(long userId, int count) {
        jdbc.sql("""
                        INSERT INTO orders (user_id, customer_name, customer_email, status, total_amount, created_at)
                        SELECT :userId, 'Nguyễn Văn A', 'a.nguyen@example.com', 'PENDING', 0,
                               now() - (:count + 1 - g) * interval '1 minute'
                        FROM generate_series(1, :count) AS g
                        """)
                .param("userId", userId).param("count", count).update();
        jdbc.sql("""
                        INSERT INTO order_items (order_id, product_id, sku, product_name, quantity, unit_price)
                        SELECT o.id, p.id, p.sku, p.name, 1, p.price
                        FROM orders o CROSS JOIN products p
                        WHERE o.user_id = :userId
                        """)
                .param("userId", userId).update();
    }

    /** Người đặt mặc định: Nguyễn Văn A, tài khoản ACTIVE. Chỉ tạo khi test thật sự cần. */
    protected long customerId() {
        if (customerId == null) {
            customerId = createUser("a.nguyen@example.com", "Nguyễn Văn A", "a.nguyen", "ACTIVE");
        }
        return customerId;
    }

    protected int stockOf(long productId) {
        return jdbc.sql("SELECT stock FROM products WHERE id = :id")
                .param("id", productId).query(Integer.class).single();
    }

    protected long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** Body đặt hàng của người đặt mặc định (customerId()). */
    protected String orderJson(long productId, int quantity) {
        return orderJson(customerId(), productId, quantity);
    }

    protected static String orderJson(long userId, long productId, int quantity) {
        return """
                {"userId":%d,"items":[{"productId":%d,"quantity":%d}]}
                """.formatted(userId, productId, quantity);
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    protected static long idOf(Map<String, Object> body) {
        return ((Number) body.get("id")).longValue();
    }
}
