package com.shoplab.order.internal;

import com.shoplab.common.AuditedEntity;
import com.shoplab.product.ReservedItem;
import com.shoplab.user.UserSummary;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Entity name là "ShopOrder" vì ORDER là từ khoá trong JPQL (ORDER BY).
 * Trong JPQL viết: select o from ShopOrder o ...
 */
@Entity(name = "ShopOrder")
@Table(name = "orders")
@SequenceGenerator(sequenceName = "orders_id_seq", allocationSize = 50)
public class Order extends AuditedEntity {

    /** Người đặt (id bên module user, không có khoá ngoại). null: đơn tạo trước khi đơn được gắn với người dùng. */
    @Column(updatable = false)
    private Long userId;

    /** Tên, email chụp từ hồ sơ người đặt lúc đặt: sửa hồ sơ sau đó không làm đổi đơn cũ. */
    @Column(nullable = false)
    private String customerName;

    @Column(nullable = false)
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO.setScale(2);

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // dành cho JPA
    }

    /** Chỉ tạo được trong package order (qua OrderService), cho người dùng đã được module user xác nhận. */
    Order(UserSummary customer) {
        Assert.notNull(customer, "customer không được null");
        Assert.hasText(customer.fullName(), "customerName không được để trống");
        Assert.hasText(customer.email(), "customerEmail không được để trống");
        this.userId = customer.id();
        this.customerName = customer.fullName();
        this.customerEmail = customer.email();
    }

    /** Thêm dòng hàng từ phần đã giữ kho: chốt sku, tên, giá tại thời điểm đặt và cộng dồn tổng tiền. */
    void addItem(ReservedItem reserved) {
        OrderItem item = new OrderItem(this, reserved);
        items.add(item);
        totalAmount = totalAmount.add(item.getLineTotal()).setScale(2, RoundingMode.HALF_UP);
    }

    public Long getUserId() { return userId; }
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
}
