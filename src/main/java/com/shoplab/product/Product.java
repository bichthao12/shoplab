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

import java.math.BigDecimal;
import java.time.Instant;

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

    public Product(String sku, String name, String description, String category,
                   BigDecimal price, int stock, boolean active) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.category = category;
        this.price = price;
        this.stock = stock;
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

    /**
     * Trừ kho khi bán. Kho không bao giờ âm: không đủ hàng → InsufficientStockException, kho giữ nguyên.
     */
    void deductStock(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity phải > 0, nhận được " + quantity);
        }
        if (stock < quantity) {
            throw new InsufficientStockException(sku, quantity, stock);
        }
        stock -= quantity;
    }

    // Thay đổi dữ liệu chỉ làm được trong package product: module khác phải đi qua ProductService.
    void setSku(String sku) { this.sku = sku; }
    void setName(String name) { this.name = name; }
    void setDescription(String description) { this.description = description; }
    void setCategory(String category) { this.category = category; }
    void setPrice(BigDecimal price) { this.price = price; }
    void setStock(int stock) { this.stock = stock; }
    void setActive(boolean active) { this.active = active; }
}
