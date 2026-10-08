package com.shoplab.order.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** Có đơn nào chứa sản phẩm này không (dùng index idx_order_items_product_id). */
    boolean existsByItemsProductId(Long productId);
}
