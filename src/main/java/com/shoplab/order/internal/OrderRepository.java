package com.shoplab.order.internal;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    // ---------------------------------------------------------------------------------------------------------------
    // Danh sách đơn phân trang cursor (keyset), mới nhất trước: GET /api/orders. Thứ tự (created_at DESC, id DESC): id
    // phân định các đơn cùng thời điểm nên thứ tự cố định. Trang sau = các đơn đứng sau đơn cuối của trang trước, viết
    // bằng so sánh bộ (created_at, id) < (?, ?): PostgreSQL dùng nó làm điểm bắt đầu quét index, nên trang 10.000 cũng
    // chỉ đọc khoảng 20 dòng index như trang 1; OFFSET thì phải đọc rồi bỏ mọi dòng của các trang trước.
    // limit = số đơn mỗi trang + 1 (OrderService.listOrders). So sánh với OFFSET: scripts/pagination-lab.sql.

    /** Trang đầu của mọi đơn (index idx_orders_created_at). */
    @Query("select o from ShopOrder o order by o.createdAt desc, o.id desc")
    List<Order> findNewest(Limit limit);

    /**
     * Các đơn đứng sau đơn (createdAt, id) trong danh sách mọi đơn. Index idx_orders_created_at chỉ có created_at:
     * PostgreSQL đi thẳng tới created_at <= ?, lọc bỏ các đơn trùng created_at có id lớn hơn, rồi xếp các đơn trùng
     * created_at theo id (Incremental Sort). Đơn hầu như không trùng created_at (tới micro giây) nên phần thêm đó gần như
     * bằng 0; nếu có hàng nghìn đơn trùng một created_at thì cần index (created_at DESC, id DESC).
     */
    @Query("""
            select o from ShopOrder o
            where (o.createdAt, o.id) < (:createdAt, :id)
            order by o.createdAt desc, o.id desc
            """)
    List<Order> findNewestAfter(@Param("createdAt") Instant createdAt, @Param("id") long id, Limit limit);

    /** Trang đầu đơn của một người dùng (index idx_orders_user_id_created_at). */
    @Query("select o from ShopOrder o where o.userId = :userId order by o.createdAt desc, o.id desc")
    List<Order> findNewestByUserId(@Param("userId") long userId, Limit limit);

    /** Các đơn của người dùng đứng sau đơn (createdAt, id). */
    @Query("""
            select o from ShopOrder o
            where o.userId = :userId and (o.createdAt, o.id) < (:createdAt, :id)
            order by o.createdAt desc, o.id desc
            """)
    List<Order> findNewestByUserIdAfter(@Param("userId") long userId, @Param("createdAt") Instant createdAt,
                                        @Param("id") long id, Limit limit);

    /** Đơn của một người dùng, phân trang offset (bản N+1 trong scripts/bugs và các test so sánh cách nạp dùng). */
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
