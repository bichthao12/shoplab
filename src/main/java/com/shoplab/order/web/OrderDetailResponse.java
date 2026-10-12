package com.shoplab.order.web;

import com.shoplab.order.internal.OrderDetail;
import com.shoplab.order.internal.PaymentEvent;
import com.shoplab.order.internal.Recommendation;
import com.shoplab.product.ProductInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * GET /api/orders/{id}/detail.
 * @param order           đơn kèm dòng hàng, như GET /api/orders/{id} (thông tin sản phẩm chụp lúc đặt)
 * @param products        thông tin HIỆN TẠI của các sản phẩm trong đơn: giá, tồn kho, đang bán hay không
 * @param paymentHistory  lịch sử thanh toán; null nếu nguồn đó không trả kịp hoặc lỗi
 * @param recommendations gợi ý; null nếu nguồn đó không trả kịp hoặc lỗi
 * @param unavailable     nguồn phụ không lấy được: paymentHistory, recommendations; rỗng nếu đủ cả
 */
public record OrderDetailResponse(
        OrderResponse order,
        List<Product> products,
        List<Payment> paymentHistory,
        List<Suggestion> recommendations,
        List<String> unavailable
) {
    public record Product(Long id, String sku, String name, BigDecimal price, int stock, boolean active) {
        static Product from(ProductInfo p) {
            return new Product(p.id(), p.sku(), p.name(), p.price(), p.stock(), p.active());
        }
    }

    public record Payment(String reference, String status, Instant at) {
        static Payment from(PaymentEvent e) {
            return new Payment(e.reference(), e.status(), e.at());
        }
    }

    public record Suggestion(String sku, String name, String reason) {
        static Suggestion from(Recommendation r) {
            return new Suggestion(r.sku(), r.name(), r.reason());
        }
    }

    static OrderDetailResponse from(OrderDetail d) {
        return new OrderDetailResponse(
                OrderResponse.from(d.order()),
                d.products().stream().map(Product::from).toList(),
                d.paymentHistory() == null ? null : d.paymentHistory().stream().map(Payment::from).toList(),
                d.recommendations() == null ? null : d.recommendations().stream().map(Suggestion::from).toList(),
                d.unavailable());
    }
}
