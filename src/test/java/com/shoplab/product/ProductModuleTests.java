package com.shoplab.product;

import com.shoplab.TestcontainersConfiguration;
import com.shoplab.common.ApiException;
import com.shoplab.product.internal.CreateProductCommand;
import com.shoplab.product.internal.Product;
import com.shoplab.product.internal.ProductService;
import com.shoplab.product.internal.UpdateProductCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Test riêng module product: Spring Modulith chỉ dựng module này (kèm module dùng chung common),
 * không có order hay idempotency. Kiểm tra API ProductInventory mà module khác dùng, và API ProductReferences
 * mà module khác cài đặt (ở đây là mock thay cho module order).
 * Mỗi test chạy trong một transaction và rollback ở cuối (reserveStock bắt buộc có transaction).
 *
 * reserveStock trừ kho bằng câu UPDATE chạy thẳng xuống DB, nên Product đã nạp trước đó trong cùng transaction
 * vẫn giữ số cũ: test đọc tồn kho bằng SQL (stockInDb) thay vì qua ProductService.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ProductModuleTests {

    @MockitoBean ProductReferences references;

    @Autowired ProductInventory inventory;
    @Autowired ProductService products;
    @Autowired JdbcClient jdbc;

    @Test
    @DisplayName("reserveStock trừ kho và trả sku, tên, giá tại thời điểm giữ hàng")
    void reserveStock_deductsStockAndReturnsSnapshot() {
        Product p = products.create(new CreateProductCommand(
                "MODULE-001", "Áo thun", null, "ao", new BigDecimal("100.00"), 10, true));

        List<ReservedItem> reserved = inventory.reserveStock(Map.of(p.getId(), 3));

        assertThat(reserved).containsExactly(
                new ReservedItem(p.getId(), "MODULE-001", "Áo thun", new BigDecimal("100.00"), 3));
        assertThat(stockInDb(p.getId())).isEqualTo(7);
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
        assertThat(stockInDb(p.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("Module khác còn tham chiếu tới sản phẩm → không xoá, 409 nêu rõ bị dùng ở đâu")
    void delete_referencedProduct_isRejected() {
        Product p = products.create(new CreateProductCommand(
                "MODULE-004", "Áo", null, "ao", BigDecimal.ONE, 1, true));
        when(references.isReferenced(p.getId())).thenReturn(true);
        when(references.referencedBy()).thenReturn("đơn hàng");

        assertThatThrownBy(() -> products.delete(p.getId()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo("data-integrity");
                    assertThat(ex.getMessage()).contains("MODULE-004").contains("đơn hàng");
                });
        assertThat(products.getById(p.getId()).getSku()).isEqualTo("MODULE-004");
    }

    @Test
    @DisplayName("Không module nào tham chiếu → xoá được")
    void delete_unreferencedProduct_isDeleted() {
        Product p = products.create(new CreateProductCommand(
                "MODULE-005", "Áo", null, "ao", BigDecimal.ONE, 1, true));

        products.delete(p.getId());

        assertThatThrownBy(() -> products.getById(p.getId())).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("reserveStock tăng version: Product nạp trước đó (số tồn kho cũ) có lưu lại thì lỗi xung đột, không ghi đè kho")
    void reserveStock_bumpsVersion_soStaleEntityCannotOverwriteStock() {
        Product p = products.create(new CreateProductCommand(
                "MODULE-006", "Áo", null, "ao", BigDecimal.ONE, 10, true));   // entity này giữ stock = 10

        inventory.reserveStock(Map.of(p.getId(), 3));                        // DB: stock = 7, version + 1

        // p vẫn là entity trong transaction này (version 0), nên bước so version với client qua được;
        // @Version chặn lúc ghi: UPDATE ... WHERE version = 0 không khớp dòng nào
        assertThatThrownBy(() -> products.update(p.getId(),
                new UpdateProductCommand(null, "Tên mới", null, null, null, null, null, p.getVersion())))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(stockInDb(p.getId())).isEqualTo(7);
    }

    private int stockInDb(long productId) {
        return jdbc.sql("SELECT stock FROM products WHERE id = :id").param("id", productId).query(Integer.class).single();
    }
}
