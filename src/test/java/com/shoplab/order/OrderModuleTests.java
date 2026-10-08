package com.shoplab.order;

import com.shoplab.TestcontainersConfiguration;
import com.shoplab.common.ApiException;
import com.shoplab.idempotency.IdempotencyService;
import com.shoplab.order.internal.CreateOrderCommand;
import com.shoplab.order.internal.CreateOrderCommand.Line;
import com.shoplab.order.internal.Order;
import com.shoplab.order.internal.OrderService;
import com.shoplab.product.ProductInventory;
import com.shoplab.product.ProductReferences;
import com.shoplab.product.ProductUnavailableException;
import com.shoplab.product.ReservedItem;
import com.shoplab.user.UserDirectory;
import com.shoplab.user.UserSummary;
import com.shoplab.user.UserUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test riêng module order: Spring Modulith chỉ dựng module này (kèm module dùng chung common).
 * API của các module mà order phụ thuộc (ProductInventory, UserDirectory, IdempotencyService) được thay bằng mock,
 * nên test không phụ thuộc cách product, user hay idempotency được cài đặt.
 * Mỗi test chạy trong một transaction và rollback ở cuối.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
@Transactional
class OrderModuleTests {

    static final long USER_ID = 800_001L;   // không cần người dùng thật trong DB: không có khoá ngoại sang users

    @MockitoBean ProductInventory inventory;
    @MockitoBean UserDirectory users;
    @MockitoBean IdempotencyService idempotency;   // OrderController cần bean này; test không đi qua HTTP

    @Autowired OrderService orders;
    @Autowired ProductReferences productReferences;   // cài đặt của module order cho API của product

    @BeforeEach
    void activeUser() {
        when(users.requireActiveUser(USER_ID))
                .thenReturn(new UserSummary(USER_ID, "Nguyễn Văn A", "a.nguyen@example.com"));
    }

    @Test
    @DisplayName("Tạo đơn: gắn người đặt, chụp tên/email của họ; gộp dòng trùng, chụp sku/tên/giá, cộng tổng tiền")
    void create_buildsOrderFromReservedItems() {
        long productId = 900_001L;   // không cần sản phẩm thật trong DB: không có khoá ngoại sang products
        when(inventory.reserveStock(Map.of(productId, 3))).thenReturn(List.of(
                new ReservedItem(productId, "MODULE-ORDER-1", "Áo thun", new BigDecimal("100.00"), 3)));

        Order order = orders.create(CreateOrderCommand.of(USER_ID,
                List.of(new Line(productId, 1), new Line(productId, 2))));

        assertThat(order.getId()).isNotNull();
        assertThat(order.getUserId()).isEqualTo(USER_ID);
        assertThat(order.getCustomerName()).isEqualTo("Nguyễn Văn A");
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

        assertThatThrownBy(() -> orders.create(CreateOrderCommand.of(USER_ID, List.of(new Line(1L, 1)))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo("invalid-order");
                    assertThat(ex.getMessage()).contains("không tồn tại");
                });
    }

    @Test
    @DisplayName("Module user báo người dùng không đặt được → 422 invalid-order, không đụng tới kho")
    void create_userUnavailable_becomesInvalidOrder_beforeReservingStock() {
        when(users.requireActiveUser(USER_ID))
                .thenThrow(new UserUnavailableException("Tài khoản của người dùng id = 800001 đang bị khoá"));

        assertThatThrownBy(() -> orders.create(CreateOrderCommand.of(USER_ID, List.of(new Line(1L, 1)))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo("invalid-order");
                    assertThat(ex.getMessage()).contains("đang bị khoá");
                });
        verifyNoInteractions(inventory);   // không khoá sản phẩm khi người đặt không hợp lệ
    }

    @Test
    @DisplayName("Danh sách đơn theo người dùng chỉ gồm đơn của người đó")
    void listByUser_returnsOnlyThatUsersOrders() {
        long otherUser = 800_002L;
        when(users.requireActiveUser(otherUser)).thenReturn(new UserSummary(otherUser, "Trần Thị B", "b@example.com"));
        when(inventory.reserveStock(any())).thenAnswer(call -> List.of(
                new ReservedItem(900_004L, "MODULE-ORDER-4", "Áo", BigDecimal.ONE, 1)));

        Order first = orders.create(CreateOrderCommand.of(USER_ID, List.of(new Line(900_004L, 1))));
        Order second = orders.create(CreateOrderCommand.of(USER_ID, List.of(new Line(900_004L, 1))));
        orders.create(CreateOrderCommand.of(otherUser, List.of(new Line(900_004L, 1))));

        assertThat(orders.listByUser(USER_ID, PageRequest.of(0, 10)).getContent())
                .extracting(Order::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    @DisplayName("Báo cho module product: sản phẩm đã có trong đơn thì đang được tham chiếu, chưa có thì không")
    void productReferences_reportProductsInOrders() {
        long ordered = 900_002L;
        long neverOrdered = 900_003L;
        when(inventory.reserveStock(Map.of(ordered, 1))).thenReturn(List.of(
                new ReservedItem(ordered, "MODULE-ORDER-2", "Áo", BigDecimal.ONE, 1)));
        orders.create(CreateOrderCommand.of(USER_ID, List.of(new Line(ordered, 1))));

        assertThat(productReferences.isReferenced(ordered)).isTrue();
        assertThat(productReferences.isReferenced(neverOrdered)).isFalse();
        assertThat(productReferences.referencedBy()).isEqualTo("đơn hàng");
    }
}
