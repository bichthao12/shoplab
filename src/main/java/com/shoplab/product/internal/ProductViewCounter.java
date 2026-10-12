package com.shoplab.product.internal;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Đếm lượt xem sản phẩm (mỗi GET /api/products/{id} thành công là một lượt), trong bộ nhớ. Đây là bean singleton: mọi
 * request, trên mọi luồng, cùng ghi vào một map, nên map phải chịu được ghi đồng thời.
 *
 *  - HashMap với put(id, getOrDefault(id, 0) + 1): đọc rồi ghi là hai bước, không nguyên tử. Hai luồng cùng đọc số 7
 *    thì cả hai ghi 8, mất một lượt. Bắn song song là đếm thiếu (ProductViewCounterTests, bản lỗi
 *    scripts/bugs/17-view-counter-hashmap.patch).
 *  - ConcurrentHashMap.merge(id, 1L, Long::sum): đọc, cộng, ghi nguyên tử theo từng khoá. Đúng, nhưng mọi luồng cùng
 *    xem một sản phẩm tranh nhau khoá của đúng một ô trong map.
 *  - computeIfAbsent + LongAdder (ở đây): mỗi sản phẩm một LongAdder, tạo một lần. increment() không khoá: khi nhiều
 *    luồng cùng cộng, mỗi luồng cộng vào một ô riêng, sum() cộng các ô lúc đọc. Nhanh nhất khi nhiều luồng cùng tăng
 *    một khoá (sản phẩm đang hot).
 *
 * Chỉ trong bộ nhớ: khởi động lại là về 0. Mỗi sản phẩm một khoá, số sản phẩm có hạn nên map không lớn mãi (khác với
 * map theo orderId, xem PaymentHistoryCache).
 */
@Component
public class ProductViewCounter {

    private final ConcurrentHashMap<Long, LongAdder> views = new ConcurrentHashMap<>();

    public void record(long productId) {
        views.computeIfAbsent(productId, id -> new LongAdder()).increment();
    }

    public long views(long productId) {
        LongAdder counter = views.get(productId);
        return counter == null ? 0 : counter.sum();
    }
}
