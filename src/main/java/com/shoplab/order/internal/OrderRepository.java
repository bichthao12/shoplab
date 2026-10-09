package com.shoplab.order.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Chỉ dùng trong package order. */
interface OrderRepository extends JpaRepository<Order, Long> {

    /** Lấy order kèm items trong 1 query (tránh N+1). */
    @Query("""
            select distinct o from ShopOrder o
            left join fetch o.items
            where o.id = :id
            """)
    Optional<Order> findWithItemsById(@Param("id") Long id);

    /** Đơn của một người dùng (dùng index idx_orders_user_id_created_at). */
    Page<Order> findByUserId(Long userId, Pageable pageable);

    /**
     * Đơn của một người dùng kèm dòng hàng, một trang, bằng MỘT câu SQL (cộng câu đếm khi trang đầy).
     * @EntityGraph thêm left join order_items vào câu phân trang. Hibernate 7 đặt phần phân trang (ORDER BY, OFFSET,
     * FETCH FIRST) vào một subquery chỉ trên orders rồi mới join order_items, nên giới hạn áp lên số đơn chứ không
     * phải số dòng sau khi join. Hibernate 5, 6 thì nạp mọi đơn của người dùng rồi cắt trang trong bộ nhớ (cảnh báo
     * HHH90003004). So sánh với JOIN FETCH, nạp theo lô...: NPlusOneFixesTests.
     */
    @EntityGraph(attributePaths = "items")
    Page<Order> findWithItemsByUserId(Long userId, Pageable pageable);

    /** Có đơn nào chứa sản phẩm này không (dùng index idx_order_items_product_id). */
    boolean existsByItemsProductId(Long productId);
}
