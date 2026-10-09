package com.shoplab.order.internal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Các cách nạp đơn kèm dòng hàng để so sánh với cách app dùng (@EntityGraph, OrderRepository.findWithItemsByUserId)
 * trong NPlusOneFixesTests. Chỉ có trong test.
 */
interface OrderFetchVariantsRepository extends Repository<Order, Long> {

    /**
     * JOIN FETCH ngay trong câu phân trang. Câu đếm phải viết riêng: Spring Data không tự suy ra câu đếm từ câu có
     * fetch join được.
     */
    @Query(value = "select o from ShopOrder o left join fetch o.items where o.userId = :userId",
           countQuery = "select count(o) from ShopOrder o where o.userId = :userId")
    Page<Order> findWithItemsJoinFetchByUserId(@Param("userId") Long userId, Pageable pageable);

    /**
     * Bước 2 của cách hai bước (lấy trang đơn trước, rồi nạp dòng hàng của cả trang bằng một câu): cách sửa quen thuộc
     * khi Hibernate (5, 6) còn phân trang câu có fetch collection trong bộ nhớ.
     */
    @Query("select o from ShopOrder o left join fetch o.items where o.id in :ids")
    List<Order> fetchItems(@Param("ids") Collection<Long> ids);
}
