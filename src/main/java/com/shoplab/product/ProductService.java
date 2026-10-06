package com.shoplab.product;

import com.shoplab.product.dto.CreateProductRequest;
import com.shoplab.product.dto.PatchProductRequest;
import com.shoplab.product.dto.ProductResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@Transactional(readOnly = true)
public class ProductService {

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
        return ProductResponse.from(repo.save(product));
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
        return ProductResponse.from(repo.saveAndFlush(p));
    }

    // ---------- DELETE ----------
    @Transactional
    public void delete(Long id) {
        Product p = findOrThrow(id);
        repo.delete(p);
        repo.flush();   // nếu sản phẩm đã nằm trong order_items → lỗi FK → 409
    }

    private Product findOrThrow(Long id) {
        return repo.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }

    /** Lưu category dạng chữ thường để lọc không phân biệt hoa thường. */
    private static String normalizeCategory(String category) {
        return category.trim().toLowerCase(Locale.ROOT);
    }
}
