package com.shoplab.product.internal;

import com.shoplab.product.ProductCatalog;
import com.shoplab.product.ProductInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Cài đặt API ProductCatalog: một câu SELECT ... WHERE id IN (...) cho cả danh sách. */
@Service
class DefaultProductCatalog implements ProductCatalog {

    private final ProductRepository products;

    DefaultProductCatalog(ProductRepository products) {
        this.products = products;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductInfo> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return products.findAllById(ids).stream()
                .sorted(Comparator.comparing(Product::getId))
                .map(p -> new ProductInfo(p.getId(), p.getSku(), p.getName(), p.getPrice(), p.getStock(), p.isActive()))
                .toList();
    }
}
