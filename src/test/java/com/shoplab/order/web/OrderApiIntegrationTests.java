package com.shoplab.order.web;

import com.shoplab.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test cho API đơn hàng (ngoài phần idempotency):
 *  - đơn hàng chụp lại thông tin sản phẩm tại thời điểm đặt,
 *  - sản phẩm không bán được khi đặt hàng → 422 invalid-order.
 */
class OrderApiIntegrationTests extends IntegrationTestBase {

    @Test
    @DisplayName("Sửa SKU, tên, giá sản phẩm sau khi đặt → đơn cũ vẫn giữ thông tin lúc đặt")
    void orderKeepsProductSnapshot_afterProductIsEdited() {
        long productId = createProduct("SNAP-001", 100_000, 10);
        HttpResponse<String> created = postOrder(newKey(), orderJson(productId, 2));
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

    @Test
    @DisplayName("Đơn có sản phẩm không tồn tại → 422 invalid-order, kho sản phẩm khác không bị trừ")
    void orderWithUnknownProduct_returns422() {
        long productId = createProduct("UNAV-001", 100_000, 10);
        String body = """
                {"customerName":"Nguyễn Văn A","customerEmail":"a.nguyen@example.com",
                 "items":[{"productId":%d,"quantity":2},{"productId":999999,"quantity":1}]}
                """.formatted(productId);

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstItem(Map<String, Object> order) {
        return ((List<Map<String, Object>>) order.get("items")).getFirst();
    }
}
