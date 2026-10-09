package com.shoplab.order.web;

import com.shoplab.IntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N+1 khi liệt kê đơn kèm dòng hàng: GET /api/orders/with-items. Đếm số câu SQL Hibernate chạy cho một request bằng
 * thống kê của Hibernate (spring.jpa.properties.hibernate.generate_statistics=true).
 * Bản N+1 (mỗi đơn tự nạp dòng hàng của nó): scripts/bugs/13-n-plus-one.patch, test ở đây đỏ với 102 câu.
 */
class OrderListWithItemsTests extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(OrderListWithItemsTests.class);

    @Autowired EntityManagerFactory emf;

    private long userId;

    /** 100 đơn, mỗi đơn 2 dòng hàng (2 sản phẩm), đơn sau mới hơn đơn trước 1 phút. Tạo bằng SQL cho nhanh. */
    @BeforeEach
    void hundredOrdersWithTwoItemsEach() {
        userId = customerId();
        createProduct("NP-1", 100_000, 1_000);
        createProduct("NP-2", 50_000, 1_000);
        jdbc.sql("""
                        INSERT INTO orders (user_id, customer_name, customer_email, status, total_amount, created_at)
                        SELECT :userId, 'Nguyễn Văn A', 'a.nguyen@example.com', 'PENDING', 150000,
                               now() - (101 - g) * interval '1 minute'
                        FROM generate_series(1, 100) AS g
                        """)
                .param("userId", userId).update();
        jdbc.sql("""
                        INSERT INTO order_items (order_id, product_id, sku, product_name, quantity, unit_price)
                        SELECT o.id, p.id, p.sku, p.name, 1, p.price
                        FROM orders o CROSS JOIN products p
                        WHERE o.user_id = :userId
                        """)
                .param("userId", userId).update();
    }

    @Test
    @DisplayName("GET /with-items, trang 100 đơn kèm dòng hàng → 3 câu SQL (trang đơn, đếm, dòng hàng), không phải 1 + 100")
    void hundredOrdersWithItems_constantNumberOfStatements() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        HttpResponse<String> r = send("GET", "/api/orders/with-items?userId=" + userId + "&size=100", null, null);
        long statements = stats.getPrepareStatementCount();
        log.info("[n-plus-one] GET /api/orders/with-items size=100: {} câu SQL", statements);

        assertThat(r.statusCode()).isEqualTo(200);
        List<Map<String, Object>> content = content(r);
        assertThat(content).hasSize(100);
        assertThat(content).allSatisfy(order -> assertThat((List<?>) order.get("items")).hasSize(2));
        assertThat(statements).as("số câu SQL cho trang 100 đơn kèm dòng hàng").isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("GET /with-items trang 2 (20 đơn): đúng 20 đơn kế tiếp, mới nhất trước, như danh sách tóm tắt; vẫn 3 câu SQL")
    void secondPageWithItems_matchesSummaryListOrder() {
        List<Object> summaryIds = content(send("GET", "/api/orders?userId=" + userId + "&page=1&size=20", null, null))
                .stream().map(order -> order.get("id")).toList();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        HttpResponse<String> r = send("GET", "/api/orders/with-items?userId=" + userId + "&page=1&size=20", null, null);
        long statements = stats.getPrepareStatementCount();

        assertThat(r.statusCode()).isEqualTo(200);
        List<Map<String, Object>> content = content(r);
        assertThat(content).extracting(order -> order.get("id")).containsExactlyElementsOf(summaryIds);
        assertThat(content).allSatisfy(order -> assertThat((List<?>) order.get("items")).hasSize(2));
        assertThat(((Map<?, ?>) json(r).get("page")).get("totalElements")).isEqualTo(100);
        assertThat(statements).as("số câu SQL cho trang 20 đơn kèm dòng hàng").isLessThanOrEqualTo(3);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content(HttpResponse<String> r) {
        return (List<Map<String, Object>>) json(r).get("content");
    }
}
