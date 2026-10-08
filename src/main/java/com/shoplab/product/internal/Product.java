package com.shoplab.product.internal;

import com.shoplab.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Sản phẩm. Entity tự giữ quy tắc của mình: mọi thay đổi đi qua các method bên dưới,
 * dữ liệu được chuẩn hoá và kiểm tra tại đây thay vì rải ở service hay DTO.
 */
@Entity
@Table(name = "products")
@SequenceGenerator(sequenceName = "products_id_seq", allocationSize = 50)
public class Product extends AuditedEntity {

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

    /**
     * Chỉ ghi lúc INSERT. Sau đó tồn kho chỉ đổi bằng câu UPDATE cộng / trừ thẳng trên DB (giữ hàng cho đơn,
     * điều chỉnh tồn kho), không qua entity. Nhờ updatable = false, câu UPDATE của entity không có cột stock:
     * Product nạp từ trước (còn giữ số tồn kho cũ) có bị sửa và lưu lại cũng không ghi đè được kho.
     */
    @Column(nullable = false, updatable = false)
    private int stock;

    @Column(nullable = false)
    private boolean active = true;

    protected Product() {
        // dành cho JPA
    }

    /** Chỉ tạo được trong package product (qua ProductService). */
    Product(CreateProductCommand command) {
        this.sku = validSku(command.sku());
        this.name = validName(command.name());
        this.description = command.description();
        this.category = validCategory(command.category());
        this.price = validPrice(command.price());
        this.stock = validStock(command.stock());
        this.active = command.active();
    }

    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
    public int getStock() { return stock; }
    public boolean isActive() { return active; }

    // ---------- Thay đổi dữ liệu: chỉ gọi được trong package product ----------

    void changeSku(String sku)                 { this.sku = validSku(sku); }
    void rename(String name)                   { this.name = validName(name); }
    void changeDescription(String description) { this.description = description; }
    void changeCategory(String category)       { this.category = validCategory(category); }
    void changePrice(BigDecimal price)         { this.price = validPrice(price); }
    void changeActive(boolean active)          { this.active = active; }

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
