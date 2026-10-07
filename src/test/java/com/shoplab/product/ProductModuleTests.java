package com.shoplab.product;

import com.shoplab.TestcontainersConfiguration;
import com.shoplab.product.internal.CreateProductCommand;
import com.shoplab.product.internal.Product;
import com.shoplab.product.internal.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test riêng module product: Spring Modulith chỉ dựng module này (kèm module dùng chung common),
 * không có order hay idempotency. Kiểm tra API ProductInventory mà module khác dùng.
 * Mỗi test chạy trong một transaction và rollback ở cuối (reserveStock bắt buộc có transaction).
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductModuleTests {

    @Autowired ProductInventory inventory;
    @Autowired ProductService products;

    @Test
    @DisplayName("reserveStock trừ kho và trả sku, tên, giá tại thời điểm giữ hàng")
    void reserveStock_deductsStockAndReturnsSnapshot() {
        Product p = products.create(new CreateProductCommand(
                "MODULE-001", "Áo thun", null, "ao", new BigDecimal("100.00"), 10, true));

        List<ReservedItem> reserved = inventory.reserveStock(Map.of(p.getId(), 3));

        assertThat(reserved).containsExactly(
                new ReservedItem(p.getId(), "MODULE-001", "Áo thun", new BigDecimal("100.00"), 3));
        assertThat(products.getById(p.getId()).getStock()).isEqualTo(7);
    }

    @Test
    @DisplayName("Sản phẩm không tồn tại hoặc đang ngừng bán → ProductUnavailableException")
    void reserveStock_unavailableProduct_isRejected() {
        Product inactive = products.create(new CreateProductCommand(
                "MODULE-002", "Áo", null, "ao", BigDecimal.ONE, 10, false));

        assertThatThrownBy(() -> inventory.reserveStock(Map.of(999_999_999L, 1)))
                .isInstanceOf(ProductUnavailableException.class);
        assertThatThrownBy(() -> inventory.reserveStock(Map.of(inactive.getId(), 1)))
                .isInstanceOf(ProductUnavailableException.class);
    }

    @Test
    @DisplayName("Không đủ hàng → InsufficientStockException, kho giữ nguyên")
    void reserveStock_insufficientStock_isRejected() {
        Product p = products.create(new CreateProductCommand(
                "MODULE-003", "Áo", null, "ao", BigDecimal.ONE, 2, true));

        assertThatThrownBy(() -> inventory.reserveStock(Map.of(p.getId(), 3)))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(products.getById(p.getId()).getStock()).isEqualTo(2);
    }
}
