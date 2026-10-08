package com.shoplab.order.web;

import com.shoplab.IntegrationTestBase;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test cho API đơn hàng (ngoài phần idempotency):
 *  - đơn hàng chụp lại thông tin người đặt và sản phẩm tại thời điểm đặt,
 *  - người dùng / sản phẩm không hợp lệ khi đặt hàng → 422 invalid-order,
 *  - danh sách đơn của một người dùng.
 */
class OrderApiIntegrationTests extends IntegrationTestBase {

    @Test
    @DisplayName("Sửa SKU, tên, giá sản phẩm sau khi đặt → đơn cũ vẫn giữ thông tin lúc đặt")
    void orderKeepsProductSnapshot_afterProductIsEdited() {
        long productId = createProduct("SNAP-001", 100_000, 10);
        HttpResponse<String> created = postOrder(newKey(), orderJson(productId, 2));
        assertThat(created.statusCode()).isEqualTo(201);

        // version vẫn 0: giữ hàng cho đơn chỉ đổi tồn kho, không đổi version của sản phẩm
        HttpResponse<String> patched = send("PATCH", "/api/products/" + productId, """
                {"sku":"SNAP-001-NEW","name":"Tên mới","price":1,"version":0}
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

    @Test
    @DisplayName("Đơn có sản phẩm không tồn tại → 422 invalid-order, kho sản phẩm khác không bị trừ")
    void orderWithUnknownProduct_returns422() {
        long productId = createProduct("UNAV-001", 100_000, 10);
        String body = """
                {"userId":%d,"items":[{"productId":%d,"quantity":2},{"productId":999999,"quantity":1}]}
                """.formatted(customerId(), productId);

        HttpResponse<String> r = postOrder(newKey(), body);

        assertProblem(r, 422, "invalid-order");
        assertThat(json(r).get("detail").toString()).contains("999999");
        assertThat(stockOf(productId)).isEqualTo(10);
        assertThat(count("orders")).isZero();
    }

    @Test
    @DisplayName("Đơn có sản phẩm đang ngừng bán → 422 invalid-order")
    void orderWithInactiveProduct_returns422() {
        long productId = createProduct("UNAV-002", 100_000, 10, false);

        HttpResponse<String> r = postOrder(newKey(), orderJson(productId, 1));

        assertProblem(r, 422, "invalid-order");
        assertThat(json(r).get("detail").toString()).contains("UNAV-002");
        assertThat(stockOf(productId)).isEqualTo(10);
    }

    // =====================================================================
    // Người đặt
    // =====================================================================

    @Test
    @DisplayName("Đơn gắn userId, chụp tên và email từ hồ sơ lúc đặt; sửa hồ sơ sau đó đơn cũ giữ nguyên")
    void orderKeepsCustomerSnapshot_afterProfileIsEdited() {
        long productId = createProduct("CUST-001", 100_000, 10);
        HttpResponse<String> created = postOrder(newKey(), orderJson(productId, 1));
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(((Number) json(created).get("userId")).longValue()).isEqualTo(customerId());

        assertThat(send("PATCH", "/api/users/" + customerId(), """
                {"fullName":"Tên mới","version":0}
                """, null).statusCode()).isEqualTo(200);

        Map<String, Object> order = json(send("GET", "/api/orders/" + idOf(json(created)), null, null));
        assertThat(((Number) order.get("userId")).longValue()).isEqualTo(customerId());
        assertThat(order.get("customerName")).isEqualTo("Nguyễn Văn A");
        assertThat(order.get("customerEmail")).isEqualTo("a.nguyen@example.com");
    }

    @Test
    @DisplayName("Người dùng không tồn tại hoặc tài khoản bị khoá → 422 invalid-order, kho không bị trừ")
    void orderForUnknownOrLockedUser_returns422() {
        long productId = createProduct("CUST-002", 100_000, 10);
        long locked = createUser("locked@example.com", "Bị khoá", "locked", "LOCKED");

        HttpResponse<String> unknown = postOrder(newKey(), orderJson(999_999_999L, productId, 1));
        assertProblem(unknown, 422, "invalid-order");
        assertThat(json(unknown).get("detail").toString()).contains("999999999").contains("không tồn tại");

        HttpResponse<String> lockedUser = postOrder(newKey(), orderJson(locked, productId, 1));
        assertProblem(lockedUser, 422, "invalid-order");
        assertThat(json(lockedUser).get("detail").toString()).contains("đang bị khoá");

        assertThat(stockOf(productId)).isEqualTo(10);
        assertThat(count("orders")).isZero();
    }

    @Test
    @DisplayName("Body kiểu cũ (customerName, customerEmail, không có userId) → 400 errors.userId")
    void legacyBodyWithoutUserId_returns400() {
        long productId = createProduct("CUST-003", 100_000, 10);

        HttpResponse<String> r = postOrder(newKey(), """
                {"customerName":"Nguyễn Văn A","customerEmail":"a.nguyen@example.com",
                 "items":[{"productId":%d,"quantity":1}]}
                """.formatted(productId));

        assertProblem(r, 400, "validation");
        assertThat(json(r).get("errors")).asInstanceOf(InstanceOfAssertFactories.MAP).containsOnlyKeys("userId");
    }

    // =====================================================================
    // Danh sách đơn của một người dùng
    // =====================================================================

    @Test
    @DisplayName("GET ?userId= → chỉ đơn của người đó, mới nhất trước, có phân trang, không kèm dòng hàng")
    void listByUser_returnsOnlyThatUsersOrdersNewestFirst() {
        long productId = createProduct("LIST-001", 100_000, 100);
        long other = createUser("b@example.com", "Trần Thị B", "b.tran", "ACTIVE");
        List<Long> mine = new ArrayList<>();
        for (int quantity = 1; quantity <= 3; quantity++) {
            mine.add(idOf(json(postOrder(newKey(), orderJson(productId, quantity)))));
        }
        assertThat(postOrder(newKey(), orderJson(other, productId, 1)).statusCode()).isEqualTo(201);

        Map<String, Object> firstPage = json(send("GET", "/api/orders?userId=" + customerId() + "&size=2", null, null));
        Map<String, Object> secondPage = json(send("GET", "/api/orders?userId=" + customerId() + "&size=2&page=1", null, null));

        assertThat(ids(firstPage)).containsExactly(mine.get(2), mine.get(1));
        assertThat(ids(secondPage)).containsExactly(mine.get(0));
        assertThat(firstPage.get("page")).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("totalElements", 3).containsEntry("totalPages", 2);
        assertThat(((Number) content(firstPage).getFirst().get("userId")).longValue()).isEqualTo(customerId());
        assertThat(content(firstPage).getFirst()).doesNotContainKey("items");
    }

    @Test
    @DisplayName("GET danh sách: thiếu userId → 400; người dùng chưa có đơn → trang rỗng")
    void listByUser_missingOrUnknownUser() {
        assertProblem(send("GET", "/api/orders", null, null), 400);

        Map<String, Object> empty = json(send("GET", "/api/orders?userId=999999999", null, null));
        assertThat(content(empty)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstItem(Map<String, Object> order) {
        return ((List<Map<String, Object>>) order.get("items")).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("content");
    }

    private static List<Long> ids(Map<String, Object> page) {
        return content(page).stream().map(o -> ((Number) o.get("id")).longValue()).toList();
    }
}
