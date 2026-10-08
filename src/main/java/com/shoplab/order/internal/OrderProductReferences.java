package com.shoplab.order.internal;

import com.shoplab.product.ProductReferences;
import org.springframework.stereotype.Component;

/** Cho module product biết sản phẩm đã có trong đơn hàng nào chưa (thay cho khoá ngoại order_items → products). */
@Component
class OrderProductReferences implements ProductReferences {

    private final OrderRepository orderRepo;

    OrderProductReferences(OrderRepository orderRepo) {
        this.orderRepo = orderRepo;
    }

    @Override
    public boolean isReferenced(long productId) {
        return orderRepo.existsByItemsProductId(productId);
    }

    @Override
    public String referencedBy() {
        return "đơn hàng";
    }
}
