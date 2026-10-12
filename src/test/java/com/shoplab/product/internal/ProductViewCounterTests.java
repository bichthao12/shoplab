package com.shoplab.product.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bắn song song vào bộ đếm lượt xem: 16 luồng, mỗi luồng 100.000 lượt cho CÙNG một sản phẩm, tổng phải là 1.600.000.
 *  - HashMap (bản ngây thơ, chỉ có trong test này): put(id, getOrDefault(id, 0) + 1) không nguyên tử, đếm thiếu.
 *  - ConcurrentHashMap.merge: đúng.
 *  - ProductViewCounter của app (ConcurrentHashMap + LongAdder): đúng.
 * Dùng luồng thường (platform thread): hệ điều hành chạy chúng song song trên mọi nhân và có thể dừng một luồng giữa
 * get và put, đúng như các request thật. Mỗi cách ghi một dòng "[race] ..." (số đếm, thời gian) vào log.
 */
class ProductViewCounterTests {

    private static final Logger log = LoggerFactory.getLogger(ProductViewCounterTests.class);

    private static final int THREADS = 16;
    private static final int VIEWS_PER_THREAD = 100_000;
    private static final long EXPECTED = (long) THREADS * VIEWS_PER_THREAD;
    private static final long PRODUCT_ID = 1L;

    @Test
    @DisplayName("HashMap trong bean singleton: bắn song song thì đếm thiếu (mất lượt xem)")
    void hashMap_losesViews() throws InterruptedException {
        Map<Long, Long> views = new HashMap<>();

        long ms = hammer(() -> views.put(PRODUCT_ID, views.getOrDefault(PRODUCT_ID, 0L) + 1));

        long counted = views.get(PRODUCT_ID);
        log.info("[race] HashMap: {} luong x {} = {}, dem duoc {} (mat {}), {} ms", THREADS, VIEWS_PER_THREAD, EXPECTED,
                counted, EXPECTED - counted, ms);
        assertThat(counted).as("HashMap: hai luồng cùng đọc một số rồi cùng ghi số + 1, một lượt bị mất").isLessThan(EXPECTED);
    }

    @Test
    @DisplayName("ConcurrentHashMap.merge: đếm đúng")
    void concurrentHashMapMerge_countsExactly() throws InterruptedException {
        Map<Long, Long> views = new ConcurrentHashMap<>();

        long ms = hammer(() -> views.merge(PRODUCT_ID, 1L, Long::sum));

        log.info("[race] ConcurrentHashMap.merge: dem duoc {}, {} ms", views.get(PRODUCT_ID), ms);
        assertThat(views.get(PRODUCT_ID)).isEqualTo(EXPECTED);
    }

    @Test
    @DisplayName("ProductViewCounter của app (ConcurrentHashMap + LongAdder): đếm đúng")
    void productViewCounter_countsExactly() throws InterruptedException {
        ProductViewCounter counter = new ProductViewCounter();

        long ms = hammer(() -> counter.record(PRODUCT_ID));

        log.info("[race] ProductViewCounter (LongAdder): dem duoc {}, {} ms", counter.views(PRODUCT_ID), ms);
        assertThat(counter.views(PRODUCT_ID)).isEqualTo(EXPECTED);
    }

    /** THREADS luồng cùng xuất phát, mỗi luồng chạy view VIEWS_PER_THREAD lần; trả về thời gian (ms). */
    private static long hammer(Runnable view) throws InterruptedException {
        CountDownLatch startingLine = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    startingLine.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < VIEWS_PER_THREAD; i++) {
                    view.run();
                }
            }));
        }
        long started = System.nanoTime();
        startingLine.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        return (System.nanoTime() - started) / 1_000_000;
    }
}
