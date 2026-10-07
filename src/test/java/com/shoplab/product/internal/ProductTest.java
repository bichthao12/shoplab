package com.shoplab.product.internal;

import com.shoplab.product.InsufficientStockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit test cho quy tắc nằm trong entity Product (không cần Spring, không cần DB). */
class ProductTest {

    @Test
    @DisplayName("Tạo sản phẩm: sku, tên bỏ khoảng trắng đầu/cuối; category về chữ thường")
    void constructor_normalizesInput() {
        Product p = new Product(
                new CreateProductCommand("  AO-1 ", " Áo thun ", "Cotton", " AO ", new BigDecimal("1.00"), 3, true));

        assertThat(p.getSku()).isEqualTo("AO-1");
        assertThat(p.getName()).isEqualTo("Áo thun");
        assertThat(p.getDescription()).isEqualTo("Cotton");
        assertThat(p.getCategory()).isEqualTo("ao");
        assertThat(p.getStock()).isEqualTo(3);
    }

    @Test
    @DisplayName("Không cho giá âm, kho âm hay trường bắt buộc để trống")
    void invariants_areEnforced() {
        assertThatThrownBy(() -> new Product(
                new CreateProductCommand("AO-1", "Áo", null, "ao", new BigDecimal("-1"), 1, true)))
                .isInstanceOf(IllegalArgumentException.class);

        Product p = product(5);
        assertThatThrownBy(() -> p.changeStock(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.rename(" \t ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.changeCategory("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.getStock()).isEqualTo(5);
        assertThat(p.getName()).isEqualTo("Áo thun");
    }

    @Test
    @DisplayName("deductStock trừ đúng số lượng")
    void deductStock_reducesStock() {
        Product p = product(10);

        p.deductStock(3);

        assertThat(p.getStock()).isEqualTo(7);
    }

    @Test
    @DisplayName("Không đủ hàng → InsufficientStockException, kho giữ nguyên")
    void deductStock_insufficient_throwsAndKeepsStock() {
        Product p = product(2);

        assertThatThrownBy(() -> p.deductStock(3))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("cần 3, còn 2");
        assertThat(p.getStock()).isEqualTo(2);
    }

    @Test
    @DisplayName("Số lượng ≤ 0 bị từ chối (không thể dùng deductStock để cộng kho)")
    void deductStock_nonPositiveQuantity_isRejected() {
        Product p = product(5);

        assertThatThrownBy(() -> p.deductStock(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.deductStock(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.getStock()).isEqualTo(5);
    }

    private static Product product(int stock) {
        return new Product(
                new CreateProductCommand("SKU-1", "Áo thun", null, "ao", new BigDecimal("100000.00"), stock, true));
    }
}
