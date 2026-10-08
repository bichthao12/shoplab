package com.shoplab.product.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Chỉ dùng trong package product; module khác truy cập sản phẩm qua ProductService. */
interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findByCategory(String category, Pageable pageable);

    boolean existsBySku(String sku);

    boolean existsBySkuAndIdNot(String sku, Long id);

    /** SELECT ... FOR UPDATE một sản phẩm: khoá dòng tới hết transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /**
     * stock = stock + delta trong một câu lệnh, chỉ khi kết quả không âm.
     * @return 1 nếu đã cộng / trừ; 0 nếu không có sản phẩm này hoặc trừ quá số đang có
     */
    @Modifying
    @Query(value = """
            UPDATE products SET stock = stock + :delta, updated_at = now()
            WHERE id = :id AND stock + :delta >= 0
            """, nativeQuery = true)
    int adjustStock(@Param("id") long id, @Param("delta") int delta);
}
