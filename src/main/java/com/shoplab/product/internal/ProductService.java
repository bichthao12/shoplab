package com.shoplab.product.internal;

import com.shoplab.common.DbConstraints;
import com.shoplab.common.StaleVersionException;
import com.shoplab.product.InsufficientStockException;
import com.shoplab.product.ProductReferences;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Quản lý sản phẩm (tạo, đọc, sửa, xoá) cho API của chính module product.
 * Nội bộ module: module khác không gọi class này, mà dùng API ProductInventory.
 * Nhận command và trả entity, không biết gì về HTTP hay DTO web.
 */
@Service
@Transactional(readOnly = true)
public class ProductService {

    private static final String UK_PRODUCTS_SKU = "uk_products_sku";

    private final ProductRepository repo;
    private final List<ProductReferences> references;

    /** references: cài đặt của các module còn tham chiếu tới sản phẩm (vd order); không có thì danh sách rỗng. */
    public ProductService(ProductRepository repo, List<ProductReferences> references) {
        this.repo = repo;
        this.references = references;
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
    /**
     * Sửa sản phẩm, chỉ khi client đang sửa đúng version hiện tại. Hai lớp chặn, cùng trả 409 concurrent-modification:
     *  - client gửi version cũ (đã có người sửa sau lần client đọc) → StaleVersionException;
     *  - có người sửa xen vào giữa lúc kiểm tra và lúc ghi → UPDATE ... WHERE version = ? không khớp dòng nào,
     *    Hibernate báo ObjectOptimisticLockingFailureException.
     */
    @Transactional
    public Product update(Long id, UpdateProductCommand changes) {
        Product p = findOrThrow(id);
        StaleVersionException.check("Sản phẩm", p, changes.expectedVersion());

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
        if (changes.active() != null)      p.changeActive(changes.active());

        // flush ngay để version và updatedAt trả về là giá trị mới
        return saveAndFlush(p);
    }

    // ---------- TỒN KHO: cộng / trừ một lượng ----------
    /**
     * Cộng (delta > 0, nhập hàng) hoặc trừ (delta < 0, hàng hỏng, kiểm kê thiếu...) tồn kho bằng MỘT câu UPDATE
     * {@code stock = stock + delta}: tính trên con số mới nhất trong DB, nên không ghi đè lượt giữ hàng hay lượt
     * điều chỉnh nào chạy cùng lúc, và không cần version. Trừ quá số đang có → InsufficientStockException (409),
     * kho giữ nguyên. Gửi lại cùng một lượt điều chỉnh không bị cộng hai lần là nhờ Idempotency-Key ở controller.
     */
    @Transactional
    public Product adjustStock(AdjustStockCommand command) {
        int updated = repo.adjustStock(command.productId(), command.delta());
        Product p = findOrThrow(command.productId());   // đọc sau câu UPDATE: thấy tồn kho mới
        if (updated == 0) {
            throw new InsufficientStockException(p.getSku(), -command.delta(), p.getStock());
        }
        return p;
    }

    // ---------- DELETE ----------
    /**
     * Không xoá sản phẩm còn được module khác tham chiếu (DB không có khoá ngoại chéo module để chặn).
     * Khoá dòng sản phẩm TRƯỚC khi hỏi: giữ hàng cho đơn (reserveStock) cũng khoá dòng này, nên
     *  - đơn đang tạo dở → lệnh xoá chờ đơn commit rồi mới hỏi, thấy đơn mới → 409;
     *  - lệnh xoá đến trước → đơn chờ, sau đó không còn thấy sản phẩm → 422 invalid-order.
     */
    @Transactional
    public void delete(Long id) {
        Product p = repo.findByIdForUpdate(id).orElseThrow(() -> new ProductNotFoundException(id));
        for (ProductReferences ref : references) {
            if (ref.isReferenced(p.getId())) {
                throw new ProductInUseException(p.getSku(), ref.referencedBy());
            }
        }
        repo.delete(p);
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
