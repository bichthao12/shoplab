package com.shoplab.order.internal;

import com.shoplab.product.ProductCatalog;
import com.shoplab.product.ProductInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Trang chi tiết đơn: gom 4 nguồn, chạy SONG SONG bằng CompletableFuture trên orderDetailExecutor (luồng ảo), nên cả
 * trang mất khoảng thời gian của nhánh chậm nhất (khoảng 400 ms) thay vì tổng các nguồn (khoảng 720 ms nếu gọi lần
 * lượt).
 *
 *   nhánh 1: đơn hàng (DB) rồi sản phẩm (module product): sản phẩm cần productId của các dòng hàng, nên chạy sau đơn
 *   nhánh 2: lịch sử thanh toán (giả lập chậm 300 ms), chỉ cần id đơn
 *   nhánh 3: gợi ý (giả lập chậm 400 ms), chỉ cần id đơn
 *
 * Mỗi lời gọi có orTimeout riêng, tính từ lúc lời gọi đó bắt đầu:
 *   - đơn, sản phẩm: bắt buộc, hạn shoplab.order-detail.required-timeout. Lỗi thì cả trang lỗi (đơn không tồn tại →
 *     404, quá hạn → 503 order-detail-timeout), hai nguồn phụ đang chạy bị huỷ ngay, không bắt request chờ thêm.
 *     Quá hạn thì chỉ bỏ kết quả, KHÔNG ngắt luồng: luồng ảo bị ngắt khi đang đọc socket thì JDK đóng luôn socket, tức
 *     đóng kết nối DB ("An I/O error occurred while sending to the backend"), pool phải mở kết nối mới. Câu SQL chạy
 *     tiếp tới xong (lâu nhất tới lock_timeout 5 giây) rồi trả kết nối về pool như bình thường.
 *   - lịch sử thanh toán, gợi ý: không bắt buộc, hạn shoplab.order-detail.optional-timeout. Quá hạn hay lỗi thì trang
 *     vẫn trả về, phần đó để trống và ghi tên vào unavailable. Quá hạn thì luồng đang chạy lời gọi bị ngắt.
 * Mỗi nguồn chạy trong transaction riêng của nó (nếu có), trên luồng riêng: không có transaction chung cho cả trang.
 */
@Service
public class OrderDetailService {

    private static final Logger log = LoggerFactory.getLogger(OrderDetailService.class);

    private final OrderService orders;
    private final ProductCatalog catalog;
    private final PaymentHistorySource paymentHistory;
    private final RecommendationSource recommendations;
    private final ExecutorService executor;
    private final Duration requiredTimeout;
    private final Duration optionalTimeout;

    OrderDetailService(OrderService orders, ProductCatalog catalog, PaymentHistorySource paymentHistory,
                       RecommendationSource recommendations,
                       @Qualifier("orderDetailExecutor") ExecutorService executor, OrderDetailProperties properties) {
        this.orders = orders;
        this.catalog = catalog;
        this.paymentHistory = paymentHistory;
        this.recommendations = recommendations;
        this.executor = executor;
        this.requiredTimeout = properties.requiredTimeout();
        this.optionalTimeout = properties.optionalTimeout();
    }

    public OrderDetail load(long orderId) {
        long started = System.nanoTime();
        Map<String, Duration> timings = new ConcurrentHashMap<>();

        // Bắt đầu cả ba nhánh ngay, không chờ nhau. Sản phẩm bắt đầu (và tính hạn) khi đã có đơn.
        CompletableFuture<Order> order = call("order", timings, requiredTimeout, false, () -> orders.getById(orderId));
        CompletableFuture<List<ProductInfo>> products = order.thenCompose(o -> call("products", timings, requiredTimeout,
                false, () -> catalog.findByIds(o.getItems().stream().map(OrderItem::getProductId).distinct().toList())));
        CompletableFuture<List<PaymentEvent>> payments = call("paymentHistory", timings, optionalTimeout, true,
                () -> paymentHistory.findByOrderId(orderId));
        CompletableFuture<List<Recommendation>> suggestions = call("recommendations", timings, optionalTimeout, true,
                () -> recommendations.forOrder(orderId));

        // Nhánh bắt buộc
        Order loadedOrder;
        List<ProductInfo> loadedProducts;
        try {
            loadedOrder = order.join();
            loadedProducts = products.join();
        } catch (CompletionException ex) {
            payments.cancel(true);
            suggestions.cancel(true);
            throw requiredFailure(ex);
        }

        // Hai nhánh phụ: mỗi lời gọi tự hết hạn (orTimeout), ở đây chỉ chờ kết quả hoặc lỗi
        List<String> unavailable = new ArrayList<>();
        List<PaymentEvent> loadedPayments = optional("paymentHistory", payments, unavailable);
        List<Recommendation> loadedSuggestions = optional("recommendations", suggestions, unavailable);

        Map<String, Duration> ordered = new LinkedHashMap<>();
        for (String source : List.of("order", "products", "paymentHistory", "recommendations")) {
            if (timings.containsKey(source)) {
                ordered.put(source, timings.get(source));
            }
        }
        ordered.put("total", Duration.ofNanos(System.nanoTime() - started));
        return new OrderDetail(loadedOrder, loadedProducts, loadedPayments, loadedSuggestions, List.copyOf(unavailable),
                ordered);
    }

    /**
     * Một lời gọi trên executor, hết hạn sau timeout (orTimeout: CompletableFuture kết thúc bằng TimeoutException).
     * interrupt: quá hạn hay bị huỷ (cancel) thì ngắt luôn luồng đang chạy lời gọi. Tự CompletableFuture không làm việc
     * đó, nó chỉ đánh dấu kết thúc, còn việc vẫn chạy tiếp tới hết. Không dùng cho lời gọi DB (xem ghi chú của lớp).
     */
    private <T> CompletableFuture<T> call(String source, Map<String, Duration> timings, Duration timeout, boolean interrupt,
                                          Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Future<?> running = executor.submit(() -> {
            try {
                result.complete(timed(source, timings, task));
            } catch (Throwable ex) {
                result.completeExceptionally(ex);
            }
        });
        result.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS).whenComplete((value, ex) -> {
            if (interrupt && (ex instanceof TimeoutException || result.isCancelled())) {
                running.cancel(true);
            }
        });
        return result;
    }

    private static <T> T timed(String source, Map<String, Duration> timings, Supplier<T> task) {
        long started = System.nanoTime();
        try {
            return task.get();
        } finally {
            timings.put(source, Duration.ofNanos(System.nanoTime() - started));
        }
    }

    /** Kết quả nguồn phụ, hoặc null (và tên nguồn vào unavailable) nếu quá hạn hay lỗi. */
    private <T> T optional(String source, CompletableFuture<T> future, List<String> unavailable) {
        try {
            return future.join();
        } catch (CompletionException | CancellationException ex) {
            if (ex.getCause() instanceof TimeoutException) {
                log.warn("Trang chi tiết đơn: {} quá {} ms, bỏ qua phần này", source, optionalTimeout.toMillis());
            } else {
                log.warn("Trang chi tiết đơn: {} lỗi, bỏ qua phần này: {}", source, String.valueOf(ex.getCause()));
            }
            unavailable.add(source);
            return null;
        }
    }

    /** Lỗi của nhánh bắt buộc: quá hạn → 503; còn lại ném đúng exception gốc (vd OrderNotFoundException → 404). */
    private RuntimeException requiredFailure(CompletionException ex) {
        if (ex.getCause() instanceof TimeoutException) {
            return new OrderDetailTimeoutException(requiredTimeout);
        }
        if (ex.getCause() instanceof RuntimeException cause) {
            return cause;
        }
        return ex;
    }
}
