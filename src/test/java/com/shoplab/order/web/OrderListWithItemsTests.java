package com.shoplab.order.web;

import com.shoplab.IntegrationTestBase;
import com.shoplab.order.internal.Order;
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
 * So sánh các cách sửa (@EntityGraph, JOIN FETCH, nạp theo lô, hai bước): order.internal.NPlusOneFixesTests.
 */
class OrderListWithItemsTests extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(OrderListWithItemsTests.class);

    @Autowired EntityManagerFactory emf;

    private long userId;

    /** 100 đơn, mỗi đơn 2 dòng hàng (2 sản phẩm), đơn sau mới hơn đơn trước 1 phút. */
    @BeforeEach
    void hundredOrdersWithTwoItemsEach() {
        userId = customerId();
        createProduct("NP-1", 100_000, 1_000);
        createProduct("NP-2", 50_000, 1_000);
        createOrdersWithItems(userId, 100);
    }

    @Test
    @DisplayName("GET /with-items, trang 100 đơn kèm dòng hàng → 2 câu SQL (trang đơn kèm dòng hàng, đếm), không phải 1 + 100")
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
        assertThat(statements).as("số câu SQL cho trang 100 đơn kèm dòng hàng").isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("GET /with-items trang 2 (20 đơn): đúng 20 đơn kế tiếp, mới nhất trước; 2 câu SQL, chỉ nạp 20 đơn")
    void secondPageWithItems_matchesSummaryListOrder() {
        List<Object> summaryIds = content(send("GET", "/api/orders?userId=" + userId + "&page=1&size=20", null, null))
                .stream().map(order -> order.get("id")).toList();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        HttpResponse<String> r = send("GET", "/api/orders/with-items?userId=" + userId + "&page=1&size=20", null, null);
        long statements = stats.getPrepareStatementCount();
        long ordersLoaded = stats.getEntityStatistics(Order.class.getName()).getLoadCount();

        assertThat(r.statusCode()).isEqualTo(200);
        List<Map<String, Object>> content = content(r);
        assertThat(content).extracting(order -> order.get("id")).containsExactlyElementsOf(summaryIds);
        assertThat(content).allSatisfy(order -> assertThat((List<?>) order.get("items")).hasSize(2));
        assertThat(((Map<?, ?>) json(r).get("page")).get("totalElements")).isEqualTo(100);
        assertThat(statements).as("số câu SQL cho trang 20 đơn kèm dòng hàng").isLessThanOrEqualTo(2);
        // Phân trang phải nằm trong SQL: nạp mọi đơn rồi cắt trang trong bộ nhớ thì con số này là 100
        assertThat(ordersLoaded).as("số đơn Hibernate nạp vào bộ nhớ cho trang 20 đơn").isEqualTo(20);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content(HttpResponse<String> r) {
        return (List<Map<String, Object>>) json(r).get("content");
    }
}
