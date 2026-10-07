package com.shoplab.product.internal;

import com.shoplab.common.DbConstraints;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản lý sản phẩm (tạo, đọc, sửa, xoá) cho API của chính module product.
 * Nội bộ module: module khác không gọi class này, mà dùng API ProductInventory.
 * Nhận command và trả entity, không biết gì về HTTP hay DTO web.
 */
@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final String UK_PRODUCTS_SKU = "uk_products_sku";
    private static final String FK_ORDER_ITEMS_PRODUCT = "fk_order_items_product";

    private final ProductRepository repo;

    public ProductService(ProductRepository repo) {
        this.repo = repo;
    }

    // ---------- CREATE ----------
    @Transactional
    public Product create(CreateProductCommand command) {
        Product product = new Product(command);
        if (repo.existsBySku(product.getSku())) {
            throw new DuplicateSkuException(product.getSku());
        }
        return saveAndFlush(product);
    }

    // ---------- READ ----------
    public Product getById(Long id) {
        return findOrThrow(id);
    }

    public Page<Product> list(String category, Pageable pageable) {
        return (category == null || category.isBlank())
                ? repo.findAll(pageable)
                : repo.findByCategory(Product.normalizeCategory(category), pageable);
    }

    // ---------- UPDATE (cập nhật một phần) ----------
    @Transactional
    public Product update(Long id, UpdateProductCommand changes) {
        Product p = findOrThrow(id);

        if (changes.sku() != null) {
            // Kiểm tra trùng TRƯỚC khi sửa entity: sửa trước thì Hibernate sẽ flush SKU mới ngay khi chạy query này
            String sku = Product.normalizeSku(changes.sku());
            if (repo.existsBySkuAndIdNot(sku, id)) {
                throw new DuplicateSkuException(sku);
            }
            p.changeSku(sku);
        }
        if (changes.name() != null)        p.rename(changes.name());
        if (changes.description() != null) p.changeDescription(changes.description());
        if (changes.category() != null)    p.changeCategory(changes.category());
        if (changes.price() != null)       p.changePrice(changes.price());
        if (changes.stock() != null)       p.changeStock(changes.stock());
        if (changes.active() != null)      p.changeActive(changes.active());

        // flush ngay để version và updatedAt trả về là giá trị mới
        return saveAndFlush(p);
    }

    // ---------- DELETE ----------
    @Transactional
    public void delete(Long id) {
        Product p = findOrThrow(id);
        try {
            repo.delete(p);
            repo.flush();
        } catch (DataIntegrityViolationException ex) {
            if (DbConstraints.isViolated(ex, FK_ORDER_ITEMS_PRODUCT)) {
                throw new ProductInUseException(p.getSku());
            }
            throw ex;
        }
    }

    private Product findOrThrow(Long id) {
        return repo.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    /**
     * Lưu và flush ngay để lỗi DB bay ra tại đây.
     * Hai request cùng SKU có thể cùng lọt qua bước kiểm tra trước; khi đó DB chặn bằng uk_products_sku
     * và lỗi vẫn được trả về là trùng SKU.
     */
    private Product saveAndFlush(Product product) {
        try {
            return repo.saveAndFlush(product);
        } catch (DataIntegrityViolationException ex) {
            if (DbConstraints.isViolated(ex, UK_PRODUCTS_SKU)) {
                throw new DuplicateSkuException(product.getSku());
            }
            throw ex;
        }
    }
}
