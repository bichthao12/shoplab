package com.shoplab.order.internal;

import com.shoplab.common.BaseEntity;
import com.shoplab.product.ReservedItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Một dòng của đơn hàng. Tham chiếu sản phẩm bằng id (không giữ entity của module product)
 * và chụp lại sku, tên, giá tại thời điểm đặt: sửa sản phẩm sau đó không làm đổi đơn cũ.
 * Bảng order_items không có version / mốc thời gian nên chỉ kế thừa BaseEntity.
 */
@Entity
@Table(name = "order_items")
@SequenceGenerator(sequenceName = "order_items_id_seq", allocationSize = 50)
public class OrderItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    /** Giá tại thời điểm đặt hàng – không đổi khi sản phẩm đổi giá. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    protected OrderItem() {
        // dành cho JPA
    }

    OrderItem(Order order, ReservedItem reserved) {
        this.order = order;
        this.productId = reserved.productId();
        this.sku = reserved.sku();
        this.productName = reserved.name();
        this.quantity = reserved.quantity();
        this.unitPrice = reserved.unitPrice();
    }

    public BigDecimal getLineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public Order getOrder() { return order; }
    public Long getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
}
