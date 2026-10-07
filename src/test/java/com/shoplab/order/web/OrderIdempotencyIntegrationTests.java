package com.shoplab.order.web;

import com.shoplab.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test cho POST /api/orders + Idempotency-Key.
 * App chạy thật trên cổng ngẫu nhiên, DB là PostgreSQL thật trong Docker (Testcontainers),
 * Flyway chạy toàn bộ migration trước khi test.
 */
class OrderIdempotencyIntegrationTests extends IntegrationTestBase {

    // =====================================================================
    // 1. Hành vi cơ bản
    // =====================================================================

    @Test
    @DisplayName("Thiếu header Idempotency-Key → 400 ProblemDetail")
    void missingHeader_returns400() {
        long productId = createProduct("BASIC-001", 100_000, 10);

        HttpResponse<String> r = postOrder(null, orderJson(productId, 2));

        assertProblem(r, 400);
        assertThat(json(r).get("detail").toString()).contains("Idempotency-Key");
        assertThat(count("orders")).isZero();
    }

    @Test
    @DisplayName("Key sai định dạng → 400")
    void invalidKey_returns400() {
        long productId = createProduct("BASIC-002", 100_000, 10);

        HttpResponse<String> r = postOrder("abc", orderJson(productId, 2));

        assertProblem(r, 400);
        assertThat(json(r).get("title")).isEqualTo("Invalid Idempotency-Key");
    }

    @Test
    @DisplayName("Lần đầu → 201, lưu key COMPLETED + mã phản hồi + header + nội dung phản hồi")
    void firstRequest_creates201_andStoresResponse() {
        long productId = createProduct("BASIC-003", 100_000, 10);
        String key = newKey();

        HttpResponse<String> r = postOrder(key, orderJson(productId, 2));

        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(r.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).matches(".*/api/orders/\\d+$"));
        assertThat(r.headers().firstValue("Idempotent-Replayed")).isEmpty();

        Map<String, Object> body = json(r);
        assertThat(new BigDecimal(body.get("totalAmount").toString())).isEqualByComparingTo("200000");
        assertThat(body.get("status")).isEqualTo("PENDING");

        Map<String, Object> row = jdbc.sql("""
                        SELECT status, response_status,
                               response_body::jsonb->>'id'      AS order_id,
                               response_headers->'Location'->>0 AS location
                        FROM idempotency_keys WHERE idem_key = :key
                        """)
                .param("key", key).query().singleRow();
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("response_status")).isEqualTo(201);
        assertThat(Long.parseLong(row.get("order_id").toString())).isEqualTo(idOf(body));
        assertThat(row.get("location")).isEqualTo(r.headers().firstValue("Location").orElseThrow());

        assertThat(stockOf(productId)).isEqualTo(8);
    }

    @Test
    @DisplayName("Gửi lại cùng key + cùng body → replay nguyên văn (body, Location), chỉ trừ kho 1 lần")
    void retrySameKey_replaysSameResponse_andDeductsStockOnce() {
        long productId = createProduct("BASIC-004", 100_000, 10);
        String key = newKey();
        String body = orderJson(productId, 2);

        HttpResponse<String> first = postOrder(key, body);
        HttpResponse<String> second = postOrder(key, body);
        HttpResponse<String> third = postOrder(key, body);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(json(first).get("customerName")).isEqualTo("Nguyễn Văn A");   // body trả về đúng UTF-8
        for (HttpResponse<String> retry : List.of(second, third)) {
            assertThat(retry.statusCode()).isEqualTo(201);
            assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
            assertThat(retry.body()).isEqualTo(first.body());                     // giống hệt từng ký tự
            assertThat(retry.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
            assertThat(retry.headers().firstValue("Content-Type")).isEqualTo(first.headers().firstValue("Content-Type"));
        }
        assertThat(first.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(count("orders")).isEqualTo(1);
        assertThat(stockOf(productId)).isEqualTo(8);
    }

    @Test
    @DisplayName("Replay trả nguyên văn body đã lưu, kể cả khi khác cấu trúc OrderResponse hiện tại (đổi DTO không làm hỏng replay)")
    void replayReturnsStoredBodyVerbatim_evenIfDtoChanged() {
        long productId = createProduct("BASIC-005", 100_000, 10);
        String key = newKey();
        String body = orderJson(productId, 1);
        HttpResponse<String> first = postOrder(key, body);
        assertThat(first.statusCode()).isEqualTo(201);

        // Giả lập bản lưu từ một phiên bản DTO cũ: cấu trúc khác OrderResponse hiện tại
        String legacyBody = """
                {"id":%d,"legacyField":"giữ nguyên"}""".formatted(idOf(json(first)));
        jdbc.sql("UPDATE idempotency_keys SET response_body = :body WHERE idem_key = :key")
                .param("body", legacyBody).param("key", key).update();

        HttpResponse<String> retry = postOrder(key, body);

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(retry.body()).isEqualTo(legacyBody);
        assertThat(retry.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
    }

    @Test
    @DisplayName("Cùng nội dung nhưng khác thứ tự item / hoa-thường email → vẫn là replay")
    void equivalentBody_isTreatedAsSameRequest() {
        long p1 = createProduct("BASIC-006A", 100_000, 10);
        long p2 = createProduct("BASIC-006B", 50_000, 10);
        String key = newKey();

        String original = """
                {"customerName":"Nguyễn Văn A","customerEmail":"a.nguyen@example.com",
                 "items":[{"productId":%d,"quantity":2},{"productId":%d,"quantity":1}]}
                """.formatted(p1, p2);
        String equivalent = """
                {"customerName":"Nguyễn Văn A","customerEmail":"A.NGUYEN@example.com",
                 "items":[{"productId":%d,"quantity":1},{"productId":%d,"quantity":1},{"productId":%d,"quantity":1}]}
                """.formatted(p2, p1, p1);

        HttpResponse<String> first = postOrder(key, original);
        HttpResponse<String> retry = postOrder(key, equivalent);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(count("orders")).isEqualTo(1);
    }

    @Test
    @DisplayName("Cùng key nhưng body khác → 422, không tạo thêm đơn")
    void sameKeyDifferentBody_returns422() {
        long productId = createProduct("BASIC-007", 100_000, 10);
        String key = newKey();

        assertThat(postOrder(key, orderJson(productId, 2)).statusCode()).isEqualTo(201);
        HttpResponse<String> r = postOrder(key, orderJson(productId, 3));

        assertProblem(r, 422);
        assertThat(json(r).get("title")).isEqualTo("Idempotency Key Reused");
        assertThat(count("orders")).isEqualTo(1);
        assertThat(stockOf(productId)).isEqualTo(8);
    }

    @Test
    @DisplayName("Cùng key, hai body khác nhau nhưng ghép chuỗi lại giống nhau (dấu | trong tên/email) → vẫn 422")
    void sameKey_bodiesThatOnlyDifferAroundSeparator_returns422() {
        long productId = createProduct("BASIC-008", 100_000, 10);
        String key = newKey();
        String first = """
                {"customerName":"A|b","customerEmail":"c@example.com","items":[{"productId":%d,"quantity":1}]}
                """.formatted(productId);
        String second = """
                {"customerName":"A","customerEmail":"b|c@example.com","items":[{"productId":%d,"quantity":1}]}
                """.formatted(productId);

        assertThat(postOrder(key, first).statusCode()).isEqualTo(201);
        HttpResponse<String> r = postOrder(key, second);

        assertProblem(r, 422, "idempotency-key-reused");
        assertThat(count("orders")).isEqualTo(1);
    }

    // =====================================================================
    // 2. Một transaction: ghi key + tạo đơn cùng commit / cùng rollback
    // =====================================================================

    @Test
    @DisplayName("Lỗi nghiệp vụ (hết hàng) → rollback cả key lẫn đơn; retry cùng key sau khi nhập kho → 201")
    void businessError_rollsBackKey_andSameKeyCanRetry() {
        long productId = createProduct("TX-001", 100_000, 1);
        String key = newKey();

        HttpResponse<String> r = postOrder(key, orderJson(productId, 5));

        assertProblem(r, 409);
        assertThat(json(r).get("title")).isEqualTo("Insufficient Stock");
        assertThat(keyExists(key)).isFalse();
        assertThat(count("orders")).isZero();
        assertThat(stockOf(productId)).isEqualTo(1);

        jdbc.sql("UPDATE products SET stock = 100 WHERE id = :id").param("id", productId).update();

        HttpResponse<String> retry = postOrder(key, orderJson(productId, 5));
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).isEmpty();
        assertThat(stockOf(productId)).isEqualTo(95);
    }

    @Test
    @DisplayName("Lỗi SAU KHI đã tạo đơn (lúc lưu response) → đơn và trừ kho cũng bị rollback")
    void failureAfterOrderCreated_rollsBackEverything() {
        long productId = createProduct("TX-002", 100_000, 10);
        String key = "boom-" + UUID.randomUUID();

        // Trigger giả lập lỗi đúng lúc UPDATE key → COMPLETED (tức là sau khi đã INSERT order + trừ kho)
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION test_fail_on_complete() RETURNS trigger AS $$
                BEGIN
                    IF NEW.idem_key LIKE 'boom-%' AND NEW.status = 'COMPLETED' THEN
                        RAISE EXCEPTION 'TEST: simulated failure after order created';
                    END IF;
                    RETURN NEW;
                END
                $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("""
                CREATE TRIGGER trg_test_fail_on_complete
                BEFORE UPDATE ON idempotency_keys
                FOR EACH ROW EXECUTE FUNCTION test_fail_on_complete()
                """).update();

        try {
            HttpResponse<String> r = postOrder(key, orderJson(productId, 2));

            assertProblem(r, 500);
            assertThat(count("orders")).isZero();            // không có đơn "mồ côi"
            assertThat(count("order_items")).isZero();
            assertThat(stockOf(productId)).isEqualTo(10);     // kho không bị trừ
            assertThat(keyExists(key)).isFalse();             // key cũng không còn
        } finally {
            jdbc.sql("DROP TRIGGER IF EXISTS trg_test_fail_on_complete ON idempotency_keys").update();
            jdbc.sql("DROP FUNCTION IF EXISTS test_fail_on_complete()").update();
        }

        // Hết lỗi → retry cùng key thành công
        assertThat(postOrder(key, orderJson(productId, 2)).statusCode()).isEqualTo(201);
        assertThat(stockOf(productId)).isEqualTo(8);
    }

    // =====================================================================
    // 3. Đồng thời
    // =====================================================================

    @Test
    @DisplayName("8 request song song CÙNG key → tất cả 201, chỉ 1 đơn, 7 bản replay")
    void concurrentSameKey_createsExactlyOneOrder() throws Exception {
        long productId = createProduct("PAR-001", 100_000, 10);
        String key = newKey();
        String body = orderJson(productId, 1);
        int n = 8;

        List<HttpResponse<String>> responses = sendConcurrently(n, i -> postOrder(key, body));

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
        long replayed = responses.stream()
                .filter(r -> r.headers().firstValue("Idempotent-Replayed").isPresent())
                .count();
        assertThat(replayed).isEqualTo(n - 1);

        Set<Long> orderIds = responses.stream().map(r -> idOf(json(r))).collect(Collectors.toSet());
        assertThat(orderIds).hasSize(1);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(stockOf(productId)).isEqualTo(9);
    }

    @Test
    @DisplayName("8 request song song KHÁC key, kho chỉ còn 5 → đúng 5 đơn thành công, không bán vượt")
    void concurrentDifferentKeys_doNotOversell() throws Exception {
        long productId = createProduct("PAR-002", 100_000, 5);
        String body = orderJson(productId, 1);

        List<HttpResponse<String>> responses = sendConcurrently(8, i -> postOrder(newKey(), body));

        long created = responses.stream().filter(r -> r.statusCode() == 201).count();
        long outOfStock = responses.stream()
                .filter(r -> r.statusCode() == 409 && "Insufficient Stock".equals(json(r).get("title")))
                .count();

        assertThat(created).isEqualTo(5);
        assertThat(outOfStock).isEqualTo(3);
        assertThat(stockOf(productId)).isZero();
        assertThat(count("orders")).isEqualTo(5);
        assertThat(count("idempotency_keys")).isEqualTo(5);   // key của 3 request lỗi đã rollback
    }

    @Test
    @DisplayName("Key đang bị transaction khác giữ quá lock_timeout → 409 + Retry-After; giữ xong → 201")
    void keyHeldByAnotherTransaction_returns409AfterLockTimeout() throws Exception {
        long productId = createProduct("LOCK-001", 100_000, 10);
        String key = newKey();

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement(
                    "INSERT INTO idempotency_keys (idem_key, request_hash, status) VALUES (?, 'x', 'IN_PROGRESS')")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }

            long start = System.nanoTime();
            HttpResponse<String> r = postOrder(key, orderJson(productId, 1));
            Duration waited = Duration.ofNanos(System.nanoTime() - start);

            assertProblem(r, 409);
            assertThat(r.headers().firstValue("Retry-After")).hasValue("1");
            assertThat(waited).isGreaterThanOrEqualTo(Duration.ofSeconds(4));   // đã chờ ~ lock_timeout 5s

            other.rollback();
        }

        HttpResponse<String> retry = postOrder(key, orderJson(productId, 1));
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(stockOf(productId)).isEqualTo(9);
    }

    // =====================================================================
    // Helpers riêng của test đồng thời
    // =====================================================================

    @FunctionalInterface
    private interface Call {
        HttpResponse<String> send(int index) throws Exception;
    }

    /** Bắn n request cùng lúc (cùng chờ 1 "tiếng súng" CountDownLatch). */
    private List<HttpResponse<String>> sendConcurrently(int n, Call call) throws Exception {
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    startSignal.await();
                    return call.send(index);
                }));
            }
            startSignal.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> f : futures) {
                responses.add(f.get(60, TimeUnit.SECONDS));
            }
            return responses;
        }
    }

    private boolean keyExists(String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency_keys WHERE idem_key = :key")
                .param("key", key).query(Long.class).single() > 0;
    }
}
