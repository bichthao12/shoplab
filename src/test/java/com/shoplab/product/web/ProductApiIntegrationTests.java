package com.shoplab.product.web;

import com.shoplab.IntegrationTestBase;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
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
 *  - lỗi ràng buộc DB được ProductService dịch sang lỗi có nghĩa.
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
                {"price":2}
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
                {"name":" \\u001F\\t "}
                """, null);
        assertProblem(blank, 400, "validation");
        assertThat(json(blank).get("errors")).asInstanceOf(InstanceOfAssertFactories.MAP).containsKey("name");

        HttpResponse<String> multiline = send("PATCH", "/api/products/" + productId, """
                {"name":"Áo thun\\nbasic"}
                """, null);
        assertThat(multiline.statusCode()).isEqualTo(200);
        assertThat(json(multiline).get("name")).isEqualTo("Áo thun\nbasic");
    }

    // =====================================================================
    // 2. Lỗi ràng buộc DB được dịch sang lỗi có nghĩa
    // =====================================================================

    @Test
    @DisplayName("Xoá sản phẩm đã có trong đơn → 409 data-integrity kèm lý do rõ ràng, sản phẩm vẫn còn")
    void deleteProductInOrder_returns409WithReason() {
        long productId = createProduct("DEL-001", 100_000, 10);
        assertThat(postOrder(newKey(), orderJson(productId, 1)).statusCode()).isEqualTo(201);

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

    @SuppressWarnings("unchecked")
    private static List<String> skus(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("content")).stream()
                .map(p -> (String) p.get("sku"))
                .toList();
    }
}
