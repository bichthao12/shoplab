package com.shoplab.order;

import com.shoplab.order.dto.CreateOrderRequest;
import com.shoplab.order.dto.CreateOrderRequest.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** Unit test cho dạng chuẩn của request đặt hàng (không cần Spring, không cần DB). */
class CreateOrderCommandTest {

    @Test
    @DisplayName("Gộp các dòng trùng sản phẩm, sắp theo productId; chuẩn hoá tên và email")
    void from_mergesLinesAndNormalizes() {
        CreateOrderRequest req = new CreateOrderRequest(" Nguyễn Văn A ", "A.Nguyen@Example.com ",
                List.of(new Item(2L, 1), new Item(1L, 2), new Item(2L, 3)));

        CreateOrderCommand command = CreateOrderCommand.from(req);

        assertThat(command.customerName()).isEqualTo("Nguyễn Văn A");
        assertThat(command.customerEmail()).isEqualTo("a.nguyen@example.com");
        assertThat(command.quantities()).containsExactly(entry(1L, 2), entry(2L, 4));
    }

    @Test
    @DisplayName("Hai request cùng nội dung (khác thứ tự dòng, tách dòng, hoa/thường email) → cùng command")
    void equivalentRequests_giveEqualCommands() {
        CreateOrderRequest original = new CreateOrderRequest("Nguyễn Văn A", "a.nguyen@example.com",
                List.of(new Item(1L, 2), new Item(2L, 1)));
        CreateOrderRequest equivalent = new CreateOrderRequest("Nguyễn Văn A ", "A.NGUYEN@example.com",
                List.of(new Item(2L, 1), new Item(1L, 1), new Item(1L, 1)));

        assertThat(CreateOrderCommand.from(equivalent)).isEqualTo(CreateOrderCommand.from(original));
    }
}
