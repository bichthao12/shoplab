package com.shoplab.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Lấy order kèm items và product trong 1 query (tránh N+1). */
    @Query("""
            select distinct o from ShopOrder o
            left join fetch o.items i
            left join fetch i.product
            where o.id = :id
            """)
    Optional<Order> findWithItemsById(@Param("id") Long id);
}
