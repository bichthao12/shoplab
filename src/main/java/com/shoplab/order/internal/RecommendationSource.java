package com.shoplab.order.internal;

import java.util.List;

/** Nguồn gợi ý sản phẩm cho trang chi tiết đơn: ở hệ thống thật là dịch vụ gợi ý (dịch vụ ngoài). */
interface RecommendationSource {

    List<Recommendation> forOrder(long orderId);
}
