package com.shoplab.order.web;

import com.shoplab.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bán chớp nhoáng: 1.000 lượt mua cùng lúc qua POST /api/orders, sản phẩm chỉ còn 1 cái.
 * Đi qua toàn bộ luồng thật: HTTP → idempotency → kiểm tra người đặt → giữ hàng (SELECT ... FOR UPDATE) → tạo đơn.
 */
// 1.000 request × ~15 dòng log SQL / transaction thì không đọc được nữa: tắt riêng cho test này
@TestPropertySource(properties = {"logging.level.sql=INFO", "logging.level.tx=INFO"})
class FlashSaleIntegrationTests extends IntegrationTestBase {

    private static final int BUYERS = 1_000;

    @Test
    @DisplayName("1.000 lượt mua, kho còn 1 → đúng 1 đơn, 999 lượt hết hàng, kho về 0, không lỗi nào khác")
    void thousandBuyersOneItem_createsExactlyOneOrder() throws Exception {
        long productId = createProduct("FLASH-001", 100_000, 1);

        Map<String, Long> outcomes = buyAtOnce(productId, BUYERS);

        assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of(
                "201", 1L,
                "409 insufficient-stock", (long) BUYERS - 1));
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("order_items")).isEqualTo(1);
        assertThat(stockOf(productId)).isZero();
        assertThat(count("idempotency_keys")).isEqualTo(1);   // key của 999 lượt hết hàng đã rollback cùng đơn
    }
}
