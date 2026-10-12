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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Trang chi tiết đơn: gom 4 nguồn, chạy SONG SONG trên orderDetailExecutor, nên cả trang mất khoảng thời gian của nhánh
 * chậm nhất (khoảng 400 ms) thay vì tổng các nguồn (khoảng 710 ms nếu gọi lần lượt).
 *
 *   nhánh 1: đơn hàng (DB) rồi sản phẩm (module product): sản phẩm cần productId của các dòng hàng, nên chạy sau đơn
 *   nhánh 2: lịch sử thanh toán (giả lập chậm 300 ms), chỉ cần id đơn
 *   nhánh 3: gợi ý (giả lập chậm 400 ms), chỉ cần id đơn
 *
 * Đơn và sản phẩm là bắt buộc: lỗi thì cả trang lỗi (đơn không tồn tại → 404), và hai nguồn phụ đang chạy bị huỷ ngay,
 * không bắt request chờ thêm. Hai nguồn phụ thì không: quá shoplab.order-detail.optional-timeout (tính từ đầu request)
 * hay lỗi thì trang vẫn trả về, phần đó để trống và ghi tên vào unavailable.
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
    private final Duration optionalTimeout;

    OrderDetailService(OrderService orders, ProductCatalog catalog, PaymentHistorySource paymentHistory,
                       RecommendationSource recommendations,
                       @Qualifier("orderDetailExecutor") ExecutorService executor, OrderDetailProperties properties) {
        this.orders = orders;
        this.catalog = catalog;
        this.paymentHistory = paymentHistory;
        this.recommendations = recommendations;
        this.executor = executor;
        this.optionalTimeout = properties.optionalTimeout();
    }

    public OrderDetail load(long orderId) {
        long started = System.nanoTime();
        Map<String, Duration> timings = new ConcurrentHashMap<>();

        // Bắt đầu cả ba nhánh ngay, không chờ nhau
        CompletableFuture<Order> order = submit("order", timings, () -> orders.getById(orderId));
        CompletableFuture<List<ProductInfo>> products = order.thenApply(o -> timed("products", timings,
                () -> catalog.findByIds(o.getItems().stream().map(OrderItem::getProductId).distinct().toList())));
        CompletableFuture<List<PaymentEvent>> payments = submit("paymentHistory", timings,
                () -> paymentHistory.findByOrderId(orderId));
        CompletableFuture<List<Recommendation>> suggestions = submit("recommendations", timings,
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
            throw unwrap(ex);
        }

        // Hai nhánh phụ: chờ tới hết hạn tính từ đầu request
        long deadline = started + optionalTimeout.toNanos();
        List<String> unavailable = new ArrayList<>();
        List<PaymentEvent> loadedPayments = awaitOptional("paymentHistory", payments, deadline, unavailable);
        List<Recommendation> loadedSuggestions = awaitOptional("recommendations", suggestions, deadline, unavailable);

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
     * Chạy task trên executor. Huỷ CompletableFuture trả về (cancel(true)) thì ngắt luôn luồng đang chạy task:
     * CompletableFuture.cancel tự nó chỉ đánh dấu huỷ, task vẫn chạy tiếp tới hết.
     */
    private <T> CompletableFuture<T> submit(String source, Map<String, Duration> timings, Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Future<?> running = executor.submit(() -> {
            try {
                result.complete(timed(source, timings, task));
            } catch (Throwable ex) {
                result.completeExceptionally(ex);
            }
        });
        result.whenComplete((value, ex) -> {
            if (result.isCancelled()) {
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

    /** Kết quả nguồn phụ, hoặc null (và tên nguồn vào unavailable) nếu quá hạn hay lỗi. Quá hạn thì huỷ nguồn đó. */
    private static <T> T awaitOptional(String source, CompletableFuture<T> future, long deadline, List<String> unavailable) {
        try {
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("Trang chi tiết đơn: {} không trả kịp, bỏ qua phần này", source);
        } catch (ExecutionException ex) {
            log.warn("Trang chi tiết đơn: {} lỗi, bỏ qua phần này: {}", source, ex.getCause().toString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
        }
        unavailable.add(source);
        return null;
    }

    /** Lỗi của nhánh bắt buộc: ném lại đúng exception gốc (vd OrderNotFoundException → 404). */
    private static RuntimeException unwrap(CompletionException ex) {
        if (ex.getCause() instanceof RuntimeException cause) {
            return cause;
        }
        return ex;
    }
}
