package com.shoplab.product;

import com.shoplab.common.DbConstraints;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Nghiệp vụ sản phẩm. Nhận command và trả entity: không biết gì về HTTP hay DTO web,
 * việc đổi sang / từ JSON là của ProductController.
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

    // ---------- GIỮ HÀNG CHO ĐƠN (API cho module order) ----------

    /**
     * Khoá các sản phẩm (SELECT ... FOR UPDATE), kiểm tra rồi trừ kho, xử lý theo thứ tự productId.
     * Bắt buộc chạy trong transaction của bên gọi: khoá được giữ tới khi bên gọi commit/rollback,
     * nên đơn hàng và việc trừ kho cùng thành công hoặc cùng huỷ.
     *
     * @param quantities productId → số lượng cần giữ
     * @return thông tin sản phẩm tại thời điểm giữ hàng (sku, tên, giá) để chụp vào đơn
     * @throws ProductUnavailableException sản phẩm không tồn tại hoặc đang ngừng bán
     * @throws InsufficientStockException  không đủ hàng
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ReservedItem> reserveStock(Map<Long, Integer> quantities) {
        Map<Long, Product> products = repo.findAllByIdInForUpdate(quantities.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<ReservedItem> reserved = new ArrayList<>();
        for (Map.Entry<Long, Integer> line : new TreeMap<>(quantities).entrySet()) {
            Long productId = line.getKey();
            int quantity = line.getValue();

            Product p = products.get(productId);
            if (p == null) {
                throw new ProductUnavailableException("Sản phẩm id = " + productId + " không tồn tại");
            }
            if (!p.isActive()) {
                throw new ProductUnavailableException("Sản phẩm " + p.getSku() + " đang ngừng bán");
            }
            p.deductStock(quantity);
            reserved.add(new ReservedItem(p.getId(), p.getSku(), p.getName(), p.getPrice(), quantity));
        }
        return reserved;
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
