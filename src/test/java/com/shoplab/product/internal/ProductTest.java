package com.shoplab.product.internal;

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

        assertThatThrownBy(() -> product(-1)).isInstanceOf(IllegalArgumentException.class);

        Product p = product(5);
        assertThatThrownBy(() -> p.rename(" \t ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.changeCategory("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.getStock()).isEqualTo(5);
        assertThat(p.getName()).isEqualTo("Áo thun");
    }

    private static Product product(int stock) {
        return new Product(
                new CreateProductCommand("SKU-1", "Áo thun", null, "ao", new BigDecimal("100000.00"), stock, true));
    }
}
