package com.shoplab.product.web;

import com.shoplab.Concurrently;
import com.shoplab.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Lượt xem sản phẩm qua API: mỗi GET /api/products/{id} thành công là một lượt, đếm đúng cả khi gọi cùng lúc. */
class ProductViewsApiTests extends IntegrationTestBase {

    @Test
    @DisplayName("300 GET /api/products/{id} cùng lúc → views = 300; đọc views không tính là một lượt xem")
    void concurrentViews_areAllCounted() throws Exception {
        long id = createProduct("VIEWS-1", 100_000, 10);

        List<HttpResponse<String>> responses = Concurrently.run(300, i -> send("GET", "/api/products/" + id, null, null));

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
        assertThat(views(id)).isEqualTo(300);
        assertThat(views(id)).as("đọc lại số lượt xem không làm tăng nó").isEqualTo(300);
    }

    @Test
    @DisplayName("Sản phẩm không tồn tại: GET và GET .../views đều 404, không tạo lượt xem")
    void unknownProduct_returns404() {
        assertProblem(send("GET", "/api/products/999999999", null, null), 404, "product-not-found");
        assertProblem(send("GET", "/api/products/999999999/views", null, null), 404, "product-not-found");
    }

    private long views(long id) {
        HttpResponse<String> r = send("GET", "/api/products/" + id + "/views", null, null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(json(r)).containsEntry("productId", (int) id);
        return ((Number) json(r).get("views")).longValue();
    }
}
