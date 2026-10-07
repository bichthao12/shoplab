package com.shoplab.product;

import com.shoplab.common.DbConstraints;
import com.shoplab.product.dto.CreateProductRequest;
import com.shoplab.product.dto.PatchProductRequest;
import com.shoplab.product.dto.ProductResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    public ProductResponse create(CreateProductRequest req) {
        String sku = req.sku().trim();
        if (repo.existsBySku(sku)) {
            throw new DuplicateSkuException(sku);
        }
        Product product = new Product(
                sku,
                req.name().trim(),
                req.description(),
                normalizeCategory(req.category()),
                req.price(),
                req.stock(),
                req.active() == null || req.active());
        return ProductResponse.from(saveAndFlush(product));
    }

    // ---------- READ ----------
    public ProductResponse getById(Long id) {
        return ProductResponse.from(findOrThrow(id));
    }

    public Page<ProductResponse> list(String category, Pageable pageable) {
        Page<Product> page = (category == null || category.isBlank())
                ? repo.findAll(pageable)
                : repo.findByCategory(normalizeCategory(category), pageable);
        return page.map(ProductResponse::from);
    }

    // ---------- PATCH (cập nhật một phần) ----------
    @Transactional
    public ProductResponse patch(Long id, PatchProductRequest req) {
        Product p = findOrThrow(id);

        if (req.sku() != null) {
            String sku = req.sku().trim();
            if (repo.existsBySkuAndIdNot(sku, id)) {
                throw new DuplicateSkuException(sku);
            }
            p.setSku(sku);
        }
        if (req.name() != null)        p.setName(req.name().trim());
        if (req.description() != null) p.setDescription(req.description());
        if (req.category() != null)    p.setCategory(normalizeCategory(req.category()));
        if (req.price() != null)       p.setPrice(req.price());
        if (req.stock() != null)       p.setStock(req.stock());
        if (req.active() != null)      p.setActive(req.active());

        // flush ngay để version và updatedAt trong response là giá trị mới
        return ProductResponse.from(saveAndFlush(p));
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

    /** Lưu category dạng chữ thường để lọc không phân biệt hoa thường. */
    private static String normalizeCategory(String category) {
        return category.trim().toLowerCase(Locale.ROOT);
    }
}
