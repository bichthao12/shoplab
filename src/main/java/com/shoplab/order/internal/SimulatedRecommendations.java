package com.shoplab.order.internal;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gợi ý GIẢ LẬP: chờ shoplab.order-detail.recommendations-delay (mặc định 400 ms) như khi gọi dịch vụ gợi ý, rồi trả
 * ba gợi ý cố định. Không đọc DB.
 */
@Component
class SimulatedRecommendations implements RecommendationSource {

    private final OrderDetailProperties properties;

    SimulatedRecommendations(OrderDetailProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<Recommendation> forOrder(long orderId) {
        SimulatedDelay.sleep(properties.recommendationsDelay(), "Gợi ý (giả lập)");
        return List.of(
                new Recommendation("GOI-Y-1", "Sản phẩm gợi ý 1", "Khách mua đơn tương tự cũng mua"),
                new Recommendation("GOI-Y-2", "Sản phẩm gợi ý 2", "Cùng loại hàng"),
                new Recommendation("GOI-Y-3", "Sản phẩm gợi ý 3", "Đang bán chạy"));
    }
}
