package com.shoplab.order.internal;

import com.shoplab.IntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sửa N+1 bằng @EntityGraph, JOIN FETCH hoặc nạp theo lô: cùng một trang (trang 2, 20 đơn, mới nhất trước) của người
 * dùng có 100 đơn, mỗi đơn 2 dòng hàng. Mỗi cách đếm: số câu SQL, số đơn và số dòng hàng Hibernate đã nạp vào bộ nhớ
 * (thống kê Hibernate). Trang đúng thì chỉ cần nạp 20 đơn, 40 dòng hàng.
 * Hibernate 7 đặt phân trang của câu có fetch collection vào một subquery trên orders, nên @EntityGraph / JOIN FETCH
 * trên câu phân trang vẫn chỉ nạp đúng một trang. Hibernate 5, 6 thì nạp mọi đơn rồi cắt trang trong bộ nhớ, log cảnh
 * báo HHH90003004: test kiểm tra không có cảnh báo đó.
 */
@ExtendWith(OutputCaptureExtension.class)
class NPlusOneFixesTests extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(NPlusOneFixesTests.class);
    private static final Pageable SECOND_PAGE =
            PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
    private static final String IN_MEMORY_PAGING = "HHH90003004";   // firstResult/maxResults ... applying in memory

    @Autowired OrderFetchVariants variants;
    @Autowired EntityManagerFactory emf;

    private long userId;
    private List<Long> expectedIds;

    record Measured(long statements, long ordersLoaded, long itemsLoaded) {}

    @BeforeEach
    void hundredOrdersWithTwoItemsEach() {
        userId = customerId();
        createProduct("NP-1", 100_000, 1_000);
        createProduct("NP-2", 50_000, 1_000);
        createOrdersWithItems(userId, 100);
        expectedIds = jdbc.sql("""
                        SELECT id FROM orders WHERE user_id = :userId
                        ORDER BY created_at DESC, id DESC OFFSET 20 LIMIT 20
                        """)
                .param("userId", userId).query(Long.class).list();
    }

    @Test
    @DisplayName("N+1: trang 20 đơn → 22 câu SQL (trang, đếm, 20 lần nạp dòng hàng)")
    void nPlusOne() {
        Measured m = measure("N+1", variants::nPlusOne);
        assertThat(m).isEqualTo(new Measured(22, 20, 40));
    }

    @Test
    @DisplayName("@EntityGraph trên câu phân trang (app đang dùng) → 2 câu: trang đơn kèm dòng hàng, đếm")
    void entityGraphOnPage(CapturedOutput output) {
        Measured m = measure("@EntityGraph trên câu phân trang (app)", variants::entityGraphOnPage);
        assertThat(m).isEqualTo(new Measured(2, 20, 40));
        assertThat(output).doesNotContain(IN_MEMORY_PAGING);
    }

    @Test
    @DisplayName("JOIN FETCH trong câu phân trang → 2 câu, như @EntityGraph")
    void joinFetchOnPage(CapturedOutput output) {
        Measured m = measure("JOIN FETCH trong câu phân trang", variants::joinFetchOnPage);
        assertThat(m).isEqualTo(new Measured(2, 20, 40));
        assertThat(output).doesNotContain(IN_MEMORY_PAGING);
    }

    @Test
    @DisplayName("Nạp theo lô (100): cùng code với N+1 → 3 câu: trang đơn, đếm, một lô dòng hàng")
    void batchFetch(CapturedOutput output) {
        Measured m = measure("Nạp theo lô (batch size 100)", variants::batchFetch);
        assertThat(m).isEqualTo(new Measured(3, 20, 40));
        assertThat(output).doesNotContain(IN_MEMORY_PAGING);
    }

    @Test
    @DisplayName("Hai bước: trang đơn, rồi JOIN FETCH dòng hàng theo id → 3 câu")
    void twoStepJoinFetch(CapturedOutput output) {
        Measured m = measure("Hai bước: trang đơn + JOIN FETCH theo id", variants::twoStepJoinFetch);
        assertThat(m).isEqualTo(new Measured(3, 20, 40));
        assertThat(output).doesNotContain(IN_MEMORY_PAGING);
    }

    /**
     * Chạy một cách, đếm bằng thống kê Hibernate. Kiểm tra luôn kết quả: đúng 20 đơn của trang 2, đúng thứ tự, đơn nào
     * cũng đủ 2 dòng hàng (đọc items ngoài transaction: chưa nạp thì LazyInitializationException).
     */
    private Measured measure(String name, BiFunction<Long, Pageable, List<Order>> fetch) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        List<Order> page = fetch.apply(userId, SECOND_PAGE);
        Measured m = new Measured(stats.getPrepareStatementCount(),
                stats.getEntityStatistics(Order.class.getName()).getLoadCount(),
                stats.getEntityStatistics(OrderItem.class.getName()).getLoadCount());
        log.info("[n-plus-one-fix] {}: {} câu SQL, nạp {} đơn và {} dòng hàng cho trang {} đơn",
                name, m.statements(), m.ordersLoaded(), m.itemsLoaded(), page.size());

        assertThat(page).extracting(Order::getId).containsExactlyElementsOf(expectedIds);
        assertThat(page).allSatisfy(order -> assertThat(order.getItems()).hasSize(2));
        return m;
    }
}
