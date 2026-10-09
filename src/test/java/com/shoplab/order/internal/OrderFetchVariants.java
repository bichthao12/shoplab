package com.shoplab.order.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Chạy từng cách nạp một trang đơn kèm dòng hàng, mỗi cách trong một transaction riêng (như một request).
 * Chỉ có trong test (NPlusOneFixesTests). Cách app dùng: @EntityGraph trên câu phân trang
 * (OrderService.listByUserWithItems).
 */
@Component
class OrderFetchVariants {

    private final OrderRepository orders;
    private final OrderFetchVariantsRepository variants;
    private final OrderService service;

    @PersistenceContext
    private EntityManager em;

    OrderFetchVariants(OrderRepository orders, OrderFetchVariantsRepository variants, OrderService service) {
        this.orders = orders;
        this.variants = variants;
        this.service = service;
    }

    /** N+1: lấy trang đơn, rồi từng đơn tự nạp dòng hàng của nó. */
    @Transactional(readOnly = true)
    public List<Order> nPlusOne(long userId, Pageable pageable) {
        Page<Order> page = orders.findByUserId(userId, pageable);
        page.forEach(order -> order.getItems().size());
        return page.getContent();
    }

    /** @EntityGraph(attributePaths = "items") trên câu phân trang: cách app đang dùng. */
    public List<Order> entityGraphOnPage(long userId, Pageable pageable) {
        return service.listByUserWithItems(userId, pageable).getContent();
    }

    /** JOIN FETCH trong câu phân trang. */
    @Transactional(readOnly = true)
    public List<Order> joinFetchOnPage(long userId, Pageable pageable) {
        return variants.findWithItemsJoinFetchByUserId(userId, pageable).getContent();
    }

    /**
     * Nạp theo lô: cùng code với N+1, nhưng lần đầu chạm items của một đơn, Hibernate nạp items của tối đa 100 đơn
     * đang có trong session bằng một câu. Ở đây bật cho riêng session này; trong app thì dùng @BatchSize(size = 100)
     * trên Order.items, hoặc spring.jpa.properties.hibernate.default_batch_fetch_size=100 cho mọi quan hệ.
     */
    @Transactional(readOnly = true)
    public List<Order> batchFetch(long userId, Pageable pageable) {
        em.unwrap(Session.class).setFetchBatchSize(100);
        Page<Order> page = orders.findByUserId(userId, pageable);
        page.forEach(order -> order.getItems().size());
        return page.getContent();
    }

    /** Hai bước: lấy trang đơn, rồi nạp dòng hàng của cả trang bằng một câu JOIN FETCH theo id. */
    @Transactional(readOnly = true)
    public List<Order> twoStepJoinFetch(long userId, Pageable pageable) {
        Page<Order> page = orders.findByUserId(userId, pageable);
        if (page.hasContent()) {
            variants.fetchItems(page.map(Order::getId).getContent());
        }
        return page.getContent();
    }
}
