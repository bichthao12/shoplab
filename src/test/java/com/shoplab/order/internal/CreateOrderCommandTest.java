package com.shoplab.order.internal;

import com.shoplab.order.internal.CreateOrderCommand.Line;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** Unit test cho dạng chuẩn của request đặt hàng (không cần Spring, không cần DB). */
class CreateOrderCommandTest {

    @Test
    @DisplayName("Gộp các dòng trùng sản phẩm, sắp theo productId")
    void of_mergesLinesSortedByProductId() {
        CreateOrderCommand command = CreateOrderCommand.of(7L,
                List.of(new Line(2L, 1), new Line(1L, 2), new Line(2L, 3)));

        assertThat(command.userId()).isEqualTo(7L);
        assertThat(command.quantities()).containsExactly(entry(1L, 2), entry(2L, 4));
    }

    @Test
    @DisplayName("Hai request cùng nội dung (khác thứ tự dòng, tách dòng) → cùng command; khác người đặt → khác")
    void equivalentRequests_giveEqualCommands() {
        CreateOrderCommand original = CreateOrderCommand.of(7L, List.of(new Line(1L, 2), new Line(2L, 1)));
        CreateOrderCommand equivalent = CreateOrderCommand.of(7L,
                List.of(new Line(2L, 1), new Line(1L, 1), new Line(1L, 1)));
        CreateOrderCommand otherUser = CreateOrderCommand.of(8L, List.of(new Line(1L, 2), new Line(2L, 1)));

        assertThat(equivalent).isEqualTo(original);
        assertThat(otherUser).isNotEqualTo(original);
    }
}
