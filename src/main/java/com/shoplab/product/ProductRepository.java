package com.shoplab.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Chỉ dùng trong package product; module khác truy cập sản phẩm qua ProductService. */
interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByCategory(String category, Pageable pageable);

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    /**
     * SELECT ... FOR UPDATE: khoá các dòng product đến hết transaction.
     * ORDER BY id để mọi transaction khoá theo cùng thứ tự → tránh deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);
}
