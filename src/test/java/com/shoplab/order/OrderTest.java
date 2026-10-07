package com.shoplab.order;

import com.shoplab.product.ReservedItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Unit test cho entity Order (không cần Spring, không cần DB). */
class OrderTest {

    @Test
    @DisplayName("Tạo đơn: tên bỏ khoảng trắng đầu/cuối, email về chữ thường; không cho để trống")
    void constructor_normalizesCustomer() {
        Order order = new Order("  Nguyễn Văn A ", " A.Nguyen@Example.COM ");

        assertThat(order.getCustomerName()).isEqualTo("Nguyễn Văn A");
        assertThat(order.getCustomerEmail()).isEqualTo("a.nguyen@example.com");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThatThrownBy(() -> new Order(" ", "a@x.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("addItem chụp sku, tên, giá lúc đặt và cộng dồn tổng tiền (2 chữ số thập phân)")
    void addItem_snapshotsProductAndAddsUpTotal() {
        Order order = new Order("Nguyễn Văn A", "a@example.com");

        order.addItem(new ReservedItem(1L, "AO-1", "Áo thun", new BigDecimal("100000.00"), 2));
        order.addItem(new ReservedItem(2L, "QUAN-1", "Quần jean", new BigDecimal("50000.50"), 3));

        assertThat(order.getItems())
                .extracting(OrderItem::getProductId, OrderItem::getSku, OrderItem::getProductName,
                        OrderItem::getQuantity, OrderItem::getUnitPrice)
                .containsExactly(
                        tuple(1L, "AO-1", "Áo thun", 2, new BigDecimal("100000.00")),
                        tuple(2L, "QUAN-1", "Quần jean", 3, new BigDecimal("50000.50")));
        assertThat(order.getTotalAmount()).isEqualByComparingTo("350001.50");
        assertThat(order.getTotalAmount().scale()).isEqualTo(2);
    }
}
