package com.shoplab.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

/**
 * Sản phẩm. Entity tự giữ quy tắc của mình: mọi thay đổi đi qua các method bên dưới,
 * dữ liệu được chuẩn hoá và kiểm tra tại đây thay vì rải ở service hay DTO.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64, unique = true)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stock;

    @Column(nullable = false)
    private boolean active = true;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Product() {
        // dành cho JPA
    }

    /** Chỉ tạo được trong package product (qua ProductService). */
    Product(String sku, String name, String description, String category,
            BigDecimal price, int stock, boolean active) {
        this.sku = validSku(sku);
        this.name = validName(name);
        this.description = description;
        this.category = validCategory(category);
        this.price = validPrice(price);
        this.stock = validStock(stock);
        this.active = active;
    }

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
    public int getStock() { return stock; }
    public boolean isActive() { return active; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    // ---------- Thay đổi dữ liệu: chỉ gọi được trong package product ----------

    void changeSku(String sku)                 { this.sku = validSku(sku); }
    void rename(String name)                   { this.name = validName(name); }
    void changeDescription(String description) { this.description = description; }
    void changeCategory(String category)       { this.category = validCategory(category); }
    void changePrice(BigDecimal price)         { this.price = validPrice(price); }
    void changeStock(int stock)                { this.stock = validStock(stock); }
    void changeActive(boolean active)          { this.active = active; }

    /**
     * Trừ kho khi bán. Kho không bao giờ âm: không đủ hàng → InsufficientStockException, kho giữ nguyên.
     */
    void deductStock(int quantity) {
        Assert.isTrue(quantity > 0, () -> "quantity phải > 0, nhận được " + quantity);
        if (stock < quantity) {
            throw new InsufficientStockException(sku, quantity, stock);
        }
        stock -= quantity;
    }

    // ---------- Quy tắc chuẩn hoá: định nghĩa MỘT lần, dùng được cả khi chưa có entity ----------
    // strip() bỏ đúng những ký tự mà @NotBlank coi là khoảng trắng, nên request qua validation luôn còn dữ liệu.

    /** SKU: bỏ khoảng trắng đầu/cuối. */
    static String normalizeSku(String sku) {
        return sku == null ? null : sku.strip();
    }

    /** Category: bỏ khoảng trắng đầu/cuối, đưa về chữ thường để lọc không phân biệt hoa/thường. */
    static String normalizeCategory(String category) {
        return category == null ? null : category.strip().toLowerCase(Locale.ROOT);
    }

    // ---------- Kiểm tra: request đã qua validation, nên lỗi ở đây là lỗi lập trình ----------

    private static String validSku(String sku) {
        String normalized = normalizeSku(sku);
        Assert.hasText(normalized, "sku không được để trống");
        return normalized;
    }

    private static String validName(String name) {
        Assert.hasText(name, "name không được để trống");
        return name.strip();
    }

    private static String validCategory(String category) {
        String normalized = normalizeCategory(category);
        Assert.hasText(normalized, "category không được để trống");
        return normalized;
    }

    private static BigDecimal validPrice(BigDecimal price) {
        Assert.isTrue(price != null && price.signum() >= 0, "price phải >= 0");
        return price;
    }

    private static int validStock(int stock) {
        Assert.isTrue(stock >= 0, "stock phải >= 0");
        return stock;
    }
}
