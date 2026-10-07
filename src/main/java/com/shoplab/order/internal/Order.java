package com.shoplab.order.internal;

import com.shoplab.common.AuditedEntity;
import com.shoplab.product.ReservedItem;
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
import java.util.Locale;

/**
 * Entity name là "ShopOrder" vì ORDER là từ khoá trong JPQL (ORDER BY).
 * Trong JPQL viết: select o from ShopOrder o ...
 */
@Entity(name = "ShopOrder")
@Table(name = "orders")
@SequenceGenerator(sequenceName = "orders_id_seq", allocationSize = 50)
public class Order extends AuditedEntity {

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

    /** Chỉ tạo được trong package order (qua OrderService). */
    Order(String customerName, String customerEmail) {
        this.customerName = normalizeCustomerName(customerName);
        this.customerEmail = normalizeEmail(customerEmail);
        Assert.hasText(this.customerName, "customerName không được để trống");
        Assert.hasText(this.customerEmail, "customerEmail không được để trống");
    }

    /** Thêm dòng hàng từ phần đã giữ kho: chốt sku, tên, giá tại thời điểm đặt và cộng dồn tổng tiền. */
    void addItem(ReservedItem reserved) {
        OrderItem item = new OrderItem(this, reserved);
        items.add(item);
        totalAmount = totalAmount.add(item.getLineTotal()).setScale(2, RoundingMode.HALF_UP);
    }

    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }

    // ---------- Quy tắc chuẩn hoá: định nghĩa MỘT lần, CreateOrderCommand cũng dùng ----------
    // strip() bỏ đúng những ký tự mà @NotBlank coi là khoảng trắng.

    /** Tên khách: bỏ khoảng trắng đầu/cuối. */
    static String normalizeCustomerName(String customerName) {
        return customerName == null ? null : customerName.strip();
    }

    /** Email: bỏ khoảng trắng đầu/cuối, đưa về chữ thường. */
    static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
