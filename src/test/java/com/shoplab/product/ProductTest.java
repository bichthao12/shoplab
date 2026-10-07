package com.shoplab.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit test cho quy tắc tồn kho trong entity Product (không cần Spring, không cần DB). */
class ProductTest {

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
        return new Product("SKU-1", "Áo thun", null, "ao", new BigDecimal("100000.00"), stock, true);
    }
}
