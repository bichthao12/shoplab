package com.shoplab.order;

import com.shoplab.TestcontainersConfiguration;
import com.shoplab.common.ApiException;
import com.shoplab.idempotency.IdempotencyService;
import com.shoplab.order.internal.CreateOrderCommand;
import com.shoplab.order.internal.CreateOrderCommand.Line;
import com.shoplab.order.internal.Order;
import com.shoplab.order.internal.OrderService;
import com.shoplab.product.ProductInventory;
import com.shoplab.product.ProductUnavailableException;
import com.shoplab.product.ReservedItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Test riêng module order: Spring Modulith chỉ dựng module này (kèm module dùng chung common).
 * API của các module mà order phụ thuộc (ProductInventory, IdempotencyService) được thay bằng mock,
 * nên test không phụ thuộc cách product hay idempotency được cài đặt.
 * Mỗi test chạy trong một transaction và rollback ở cuối.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
@Transactional
class OrderModuleTests {

    @MockitoBean ProductInventory inventory;
    @MockitoBean IdempotencyService idempotency;   // OrderController cần bean này; test không đi qua HTTP

    @Autowired OrderService orders;
    @Autowired JdbcClient jdbc;

    @Test
    @DisplayName("Tạo đơn từ phần hàng module product giữ được: gộp dòng trùng, chụp sku/tên/giá, cộng tổng tiền")
    void create_buildsOrderFromReservedItems() {
        long productId = insertProductRow("MODULE-ORDER-1");
        when(inventory.reserveStock(Map.of(productId, 3))).thenReturn(List.of(
                new ReservedItem(productId, "MODULE-ORDER-1", "Áo thun", new BigDecimal("100.00"), 3)));

        Order order = orders.create(CreateOrderCommand.of("Nguyễn Văn A", "A.Nguyen@Example.com",
                List.of(new Line(productId, 1), new Line(productId, 2))));

        assertThat(order.getId()).isNotNull();
        assertThat(order.getCustomerEmail()).isEqualTo("a.nguyen@example.com");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("300.00");
        assertThat(order.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(productId);
            assertThat(item.getSku()).isEqualTo("MODULE-ORDER-1");
            assertThat(item.getProductName()).isEqualTo("Áo thun");
            assertThat(item.getQuantity()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("Module product báo sản phẩm không bán được → lỗi 422 invalid-order của module order")
    void create_productUnavailable_becomesInvalidOrder() {
        when(inventory.reserveStock(any())).thenThrow(new ProductUnavailableException("Sản phẩm id = 1 không tồn tại"));

        assertThatThrownBy(() -> orders.create(
                CreateOrderCommand.of("Nguyễn Văn A", "a@example.com", List.of(new Line(1L, 1)))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo("invalid-order");
                    assertThat(ex.getMessage()).contains("không tồn tại");
                });
    }

    /** order_items vẫn có FK tới products (giữ FK theo thiết kế), nên dòng đơn cần một sản phẩm có thật trong DB. */
    private long insertProductRow(String sku) {
        return jdbc.sql("""
                        INSERT INTO products (sku, name, category, price, stock)
                        VALUES (:sku, 'Áo thun', 'ao', 100, 10)
                        RETURNING id
                        """)
                .param("sku", sku)
                .query(Long.class)
                .single();
    }
}
