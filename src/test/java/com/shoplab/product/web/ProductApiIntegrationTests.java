package com.shoplab.product.web;

import com.shoplab.Concurrently;
import com.shoplab.IntegrationTestBase;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test cho API sản phẩm:
 *  - dữ liệu được chuẩn hoá một chỗ (entity) và lưu đúng từng trường,
 *  - validation khớp với cách chuẩn hoá,
 *  - lỗi ràng buộc DB được ProductService dịch sang lỗi có nghĩa,
 *  - PATCH bắt buộc gửi version đã đọc: không ai ghi đè được thay đổi (hay lượt giữ hàng) xảy ra sau lần mình đọc.
 */
class ProductApiIntegrationTests extends IntegrationTestBase {

    // =====================================================================
    // 1. Tạo / sửa: chuẩn hoá và lưu đúng từng trường
    // =====================================================================

    @Test
    @DisplayName("POST → mọi trường lưu đúng chỗ; sku, tên bỏ khoảng trắng; category về chữ thường")
    void create_storesEveryFieldNormalized() {
        HttpResponse<String> created = send("POST", "/api/products", """
                {"sku":"  AO-THUN-001 ","name":" Áo thun basic ","description":"Cotton 100%",
                 "category":" AO ","price":199000,"stock":50,"active":false}
                """, null);
        assertThat(created.statusCode()).isEqualTo(201);

        String path = URI.create(created.headers().firstValue("Location").orElseThrow()).getPath();
        Map<String, Object> p = json(send("GET", path, null, null));

        assertThat(p.get("sku")).isEqualTo("AO-THUN-001");
        assertThat(p.get("name")).isEqualTo("Áo thun basic");
        assertThat(p.get("description")).isEqualTo("Cotton 100%");
        assertThat(p.get("category")).isEqualTo("ao");
        assertThat(new BigDecimal(p.get("price").toString())).isEqualByComparingTo("199000");
        assertThat(p.get("stock")).isEqualTo(50);
        assertThat(p.get("active")).isEqualTo(false);
    }

    @Test
    @DisplayName("Auditing: tạo → có createdAt, updatedAt, version 0; sửa → version tăng, updatedAt đổi, createdAt giữ nguyên")
    void auditFields_areFilledOnCreateAndUpdate() {
        Map<String, Object> created = json(send("POST", "/api/products", """
                {"sku":"AUDIT-001","name":"Áo","category":"ao","price":1,"stock":1}
                """, null));
        assertThat(created.get("version")).isEqualTo(0);
        Instant createdAt = Instant.parse(created.get("createdAt").toString());
        assertThat(Instant.parse(created.get("updatedAt").toString())).isEqualTo(createdAt);

        Map<String, Object> updated = json(send("PATCH", "/api/products/" + idOf(created), """
                {"price":2,"version":0}
                """, null));

        assertThat(updated.get("version")).isEqualTo(1);
        assertThat(Instant.parse(updated.get("createdAt").toString())).isEqualTo(createdAt);
        assertThat(Instant.parse(updated.get("updatedAt").toString())).isAfter(createdAt);
    }

    @Test
    @DisplayName("Lọc theo category không phân biệt hoa/thường và khoảng trắng")
    void listByCategory_usesSameNormalization() {
        assertThat(send("POST", "/api/products", """
                {"sku":"QUAN-001","name":"Quần jean","category":"Quan","price":1,"stock":1}
                """, null).statusCode()).isEqualTo(201);

        HttpResponse<String> r = send("GET", "/api/products?category=%20QUAN%20", null, null);

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(skus(json(r))).containsExactly("QUAN-001");
    }

    @Test
    @DisplayName("PATCH giá trị chỉ có khoảng trắng → 400; giá trị nhiều dòng vẫn hợp lệ")
    void patch_blankIsRejected_multilineIsAccepted() {
        long productId = createProduct("PATCH-001", 100_000, 10);

        HttpResponse<String> blank = send("PATCH", "/api/products/" + productId, """
                {"name":" \\u001F\\t ","version":0}
                """, null);
        assertProblem(blank, 400, "validation");
        assertThat(json(blank).get("errors")).asInstanceOf(InstanceOfAssertFactories.MAP).containsKey("name");

        HttpResponse<String> multiline = send("PATCH", "/api/products/" + productId, """
                {"name":"Áo thun\\nbasic","version":0}
                """, null);
        assertThat(multiline.statusCode()).isEqualTo(200);
        assertThat(json(multiline).get("name")).isEqualTo("Áo thun\nbasic");
    }

    // =====================================================================
    // 2. Ràng buộc dữ liệu: trùng SKU (DB chặn), xoá sản phẩm đã có trong đơn (code chặn)
    // =====================================================================

    @Test
    @DisplayName("Xoá sản phẩm đã có trong đơn → 409 data-integrity kèm lý do rõ ràng, sản phẩm vẫn còn (không cần khoá ngoại)")
    void deleteProductInOrder_returns409WithReason() {
        long productId = createProduct("DEL-001", 100_000, 10);
        assertThat(postOrder(newKey(), orderJson(productId, 1)).statusCode()).isEqualTo(201);

        HttpResponse<String> r = send("DELETE", "/api/products/" + productId, null, null);

        assertProblem(r, 409, "data-integrity");
        assertThat(json(r).get("detail").toString()).contains("DEL-001").contains("đơn hàng");
        assertThat(count("products")).isEqualTo(1);
    }

    @Test
    @DisplayName("Đơn đang tạo dở cùng lúc với lệnh xoá → lệnh xoá chờ đơn commit rồi mới kiểm tra, vẫn trả 409")
    void deleteWhileOrderInProgress_waitsAndReturns409() throws Exception {
        long productId = createProduct("RACE-DEL-001", 100_000, 10);

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            // Giống một đơn đang tạo: đã khoá sản phẩm (reserveStock), đã ghi dòng đơn, chưa commit
            try (PreparedStatement lock = other.prepareStatement("SELECT id FROM products WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, productId);
                lock.executeQuery().close();
            }
            try (PreparedStatement order = other.prepareStatement("""
                    WITH o AS (
                        INSERT INTO orders (customer_name, customer_email) VALUES ('A', 'a@example.com') RETURNING id
                    )
                    INSERT INTO order_items (order_id, product_id, quantity, unit_price, sku, product_name)
                    SELECT o.id, ?, 1, 100000, 'RACE-DEL-001', 'Sản phẩm RACE-DEL-001' FROM o
                    """)) {
                order.setLong(1, productId);
                order.executeUpdate();
            }

            CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(() ->
                    send("DELETE", "/api/products/" + productId, null, null));
            awaitSessionWaitingForLock();   // lệnh xoá đang chờ khoá sản phẩm, chưa kiểm tra đơn
            other.commit();

            assertProblem(pending.get(30, TimeUnit.SECONDS), 409, "data-integrity");
        }
        assertThat(count("products")).isEqualTo(1);
    }

    @Test
    @DisplayName("Tạo hoặc PATCH sang SKU đã có (kể cả có khoảng trắng) → 409 duplicate-sku, không sản phẩm nào đổi")
    void duplicateSku_onCreateAndPatch_returns409() {
        createProduct("DUP-001", 100_000, 1);
        long other = createProduct("DUP-002", 100_000, 1);

        assertProblem(send("POST", "/api/products", """
                {"sku":" DUP-001 ","name":"Trùng","category":"test","price":1,"stock":1}
                """, null), 409, "duplicate-sku");
        assertProblem(send("PATCH", "/api/products/" + other, """
                {"sku":"DUP-001","version":0}
                """, null), 409, "duplicate-sku");

        assertThat(count("products")).isEqualTo(2);
        assertThat(json(send("GET", "/api/products/" + other, null, null)).get("sku")).isEqualTo("DUP-002");
    }

    @Test
    @DisplayName("PATCH sang SKU lọt qua bước kiểm tra trước (SKU đó đang được tạo, chưa commit) → DB chặn, vẫn trả 409 duplicate-sku")
    void duplicateSkuOnPatchCaughtByDatabase_returnsDuplicateSku() throws Exception {
        long productId = createProduct("RACE-PATCH-OLD", 100_000, 1);

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement("""
                    INSERT INTO products (sku, name, category, price, stock)
                    VALUES ('RACE-PATCH', 'Bản đến trước', 'test', 1, 1)
                    """)) {
                ps.executeUpdate();     // chưa commit: request bên dưới không thấy dòng này ở bước existsBySkuAndIdNot
            }

            CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(() ->
                    send("PATCH", "/api/products/" + productId, """
                            {"sku":"RACE-PATCH","version":0}
                            """, null));
            awaitSessionWaitingForLock();   // request đã tới UPDATE và đang chờ unique index
            other.commit();

            assertProblem(pending.get(30, TimeUnit.SECONDS), 409, "duplicate-sku");
        }
        assertThat(json(send("GET", "/api/products/" + productId, null, null)).get("sku")).isEqualTo("RACE-PATCH-OLD");
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
    // 3. Sửa đồng thời: PATCH bắt buộc gửi version đã đọc
    // =====================================================================

    @Test
    @DisplayName("PATCH không có version → 400 validation với errors.version, sản phẩm giữ nguyên")
    void patch_withoutVersion_returns400() {
        long productId = createProduct("VER-001", 100_000, 10);

        HttpResponse<String> r = send("PATCH", "/api/products/" + productId, """
                {"price":1}
                """, null);

        assertProblem(r, 400, "validation");
        assertThat(json(r).get("errors")).asInstanceOf(InstanceOfAssertFactories.MAP).containsOnlyKeys("version");
        assertThat(json(send("GET", "/api/products/" + productId, null, null)).get("version")).isEqualTo(0);
    }

    @Test
    @DisplayName("PATCH có stock → 400, chỉ đường sang stock-adjustments; tồn kho giữ nguyên")
    void patchStock_returns400PointingToStockAdjustments() {
        long productId = createProduct("VER-002", 100_000, 10);

        HttpResponse<String> r = send("PATCH", "/api/products/" + productId, """
                {"stock":15,"version":0}
                """, null);

        assertProblem(r, 400, "validation");
        assertThat(json(r).get("errors")).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("stock")
                .extractingByKey("stock").asString().contains("stock-adjustments");
        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("Đơn hàng không đổi version: admin sửa giá theo version đọc trước khi có đơn vẫn được, kho giữ đúng số sau đơn")
    void orderDoesNotChangeVersion_adminEditReadBeforeOrderSucceeds() {
        long productId = createProduct("VER-004", 100_000, 10);
        Map<String, Object> adminView = json(send("GET", "/api/products/" + productId, null, null));
        assertThat(adminView).containsEntry("stock", 10).containsEntry("version", 0);

        // Có đơn mua 3 cái sau lần admin đọc: stock 10 → 7, version vẫn 0
        assertThat(postOrder(newKey(), orderJson(productId, 3)).statusCode()).isEqualTo(201);
        assertThat(json(send("GET", "/api/products/" + productId, null, null)))
                .containsEntry("stock", 7).containsEntry("version", 0);

        Map<String, Object> repriced = json(send("PATCH", "/api/products/" + productId, """
                {"price":90000,"version":0}
                """, null));

        assertThat(repriced).containsEntry("stock", 7).containsEntry("version", 1);
        assertThat(stockOf(productId)).isEqualTo(7);
    }

    @Test
    @DisplayName("20 PATCH cùng lúc, cùng đọc version 0 → đúng 1 lượt thành công, 19 lượt 409, không lượt nào ghi đè lượt khác")
    void concurrentPatchesWithSameVersion_onlyOneWins() throws Exception {
        long productId = createProduct("VER-003", 100_000, 10);

        List<HttpResponse<String>> responses = Concurrently.run(20, i -> send("PATCH", "/api/products/" + productId, """
                {"price":%d,"version":0}
                """.formatted(1_000 + i), null));

        List<HttpResponse<String>> won = responses.stream().filter(r -> r.statusCode() == 200).toList();
        assertThat(won).hasSize(1);
        assertThat(responses).filteredOn(r -> r.statusCode() != 200)
                .hasSize(19)
                .allSatisfy(r -> assertProblem(r, 409, "concurrent-modification"));
        Map<String, Object> product = json(send("GET", "/api/products/" + productId, null, null));
        assertThat(new BigDecimal(product.get("price").toString()))
                .isEqualByComparingTo(json(won.getFirst()).get("price").toString());
        assertThat(product.get("version")).isEqualTo(1);
    }

    // =====================================================================
    // 4. Điều chỉnh tồn kho: POST /api/products/{id}/stock-adjustments (cộng / trừ, bắt buộc Idempotency-Key)
    // =====================================================================

    @Test
    @DisplayName("delta +5 rồi -3 → kho 10 → 15 → 12; version không đổi (tồn kho không thuộc version)")
    void stockAdjustment_addsAndSubtracts() {
        long productId = createProduct("ADJ-001", 100_000, 10);

        HttpResponse<String> added = adjustStock(productId, 5, newKey());
        assertThat(added.statusCode()).isEqualTo(200);
        assertThat(json(added)).containsEntry("stock", 15).containsEntry("version", 0);

        assertThat(json(adjustStock(productId, -3, newKey()))).containsEntry("stock", 12).containsEntry("version", 0);
        assertThat(stockOf(productId)).isEqualTo(12);
    }

    @Test
    @DisplayName("Trừ quá tồn kho → 409 insufficient-stock (requested, available), kho giữ nguyên")
    void stockAdjustment_belowZero_returns409() {
        long productId = createProduct("ADJ-002", 100_000, 10);

        HttpResponse<String> r = adjustStock(productId, -11, newKey());

        assertProblem(r, 409, "insufficient-stock");
        assertThat(json(r)).containsEntry("sku", "ADJ-002").containsEntry("requested", 11).containsEntry("available", 10);
        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("Gửi lại cùng Idempotency-Key → nhận lại response lần đầu, kho chỉ cộng 1 lần; cùng key khác delta → 422")
    void stockAdjustment_sameKey_appliedOnce() {
        long productId = createProduct("ADJ-003", 100_000, 10);
        String key = newKey();

        HttpResponse<String> first = adjustStock(productId, 5, key);
        HttpResponse<String> retry = adjustStock(productId, 5, key);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(stockOf(productId)).isEqualTo(15);

        assertProblem(adjustStock(productId, 6, key), 422, "idempotency-key-reused");
        assertThat(stockOf(productId)).isEqualTo(15);
    }

    @Test
    @DisplayName("Thiếu Idempotency-Key, delta thiếu / bằng 0 / quá lớn → 400; sản phẩm không tồn tại → 404")
    void stockAdjustment_invalidRequests() {
        long productId = createProduct("ADJ-004", 100_000, 10);

        assertProblem(send("POST", "/api/products/" + productId + "/stock-adjustments", """
                {"delta":5}
                """, null), 400);
        assertThat(errorKeys(send("POST", "/api/products/" + productId + "/stock-adjustments", "{}", newKey())))
                .containsOnlyKeys("delta");
        assertThat(errorKeys(adjustStock(productId, 0, newKey()))).containsOnlyKeys("deltaNonZero");
        assertThat(errorKeys(adjustStock(productId, 1_000_001, newKey()))).containsOnlyKeys("delta");
        assertProblem(adjustStock(999_999_999L, 5, newKey()), 404, "product-not-found");
        assertThat(stockOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("20 lượt nhập +1 và 10 đơn mua 1 cái chạy cùng lúc → đều thành công, kho = 10 + 20 - 10, không mất lượt nào")
    void restocksAndOrdersAtOnce_loseNoUpdate() throws Exception {
        long productId = createProduct("ADJ-005", 100_000, 10);
        String order = orderJson(productId, 1);

        List<HttpResponse<String>> responses = Concurrently.run(30, i -> i < 20
                ? adjustStock(productId, 1, newKey())
                : postOrder(newKey(), order));

        assertThat(responses.subList(0, 20)).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(responses.subList(20, 30)).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(201));
        assertThat(stockOf(productId)).isEqualTo(20);
        assertThat(count("orders")).isEqualTo(10);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private HttpResponse<String> adjustStock(long productId, int delta, String idempotencyKey) {
        return send("POST", "/api/products/" + productId + "/stock-adjustments", """
                {"delta":%d}
                """.formatted(delta), idempotencyKey);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> errorKeys(HttpResponse<String> r) {
        assertProblem(r, 400, "validation");
        return (Map<String, Object>) json(r).get("errors");
    }

    @SuppressWarnings("unchecked")
    private static List<String> skus(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("content")).stream()
                .map(p -> (String) p.get("sku"))
                .toList();
    }
}
