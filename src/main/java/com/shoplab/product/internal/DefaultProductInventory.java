package com.shoplab.product.internal;

import com.shoplab.product.ProductInventory;
import com.shoplab.product.ProductUnavailableException;
import com.shoplab.product.ReservedItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Cài đặt API ProductInventory: SELECT ... FOR UPDATE rồi để Product tự trừ kho. */
@Service
class DefaultProductInventory implements ProductInventory {

    private final ProductRepository repo;

    DefaultProductInventory(ProductRepository repo) {
        this.repo = repo;
    }

    @Override
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
}
