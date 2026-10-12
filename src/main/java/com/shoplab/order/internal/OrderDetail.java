package com.shoplab.order.internal;

import com.shoplab.product.ProductInfo;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Dữ liệu trang chi tiết đơn, gom từ 4 nguồn.
 * @param order          đơn kèm dòng hàng (thông tin sản phẩm chụp lúc đặt)
 * @param products       thông tin hiện tại của các sản phẩm trong đơn (giá, tồn kho, đang bán)
 * @param paymentHistory lịch sử thanh toán; null nếu nguồn đó không trả kịp hoặc lỗi
 * @param recommendations gợi ý; null nếu nguồn đó không trả kịp hoặc lỗi
 * @param unavailable    tên các nguồn phụ không lấy được (paymentHistory, recommendations)
 * @param timings        thời gian từng nguồn và cả trang (total), theo thứ tự order, products, paymentHistory,
 *                       recommendations, total
 */
public record OrderDetail(
        Order order,
        List<ProductInfo> products,
        List<PaymentEvent> paymentHistory,
        List<Recommendation> recommendations,
        List<String> unavailable,
        Map<String, Duration> timings
) {}
