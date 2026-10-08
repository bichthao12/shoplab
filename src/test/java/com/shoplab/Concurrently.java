package com.shoplab;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Chạy N tác vụ cùng lúc, để dựng tình huống tranh chấp trong test (vd N request cùng Idempotency-Key).
 *
 *  - Mỗi tác vụ chạy trên một virtual thread riêng: tạo luồng gần như không tốn gì,
 *    nên N luồng luôn chạy song song mà không phải chọn kích thước pool.
 *  - Một CountDownLatch(N) làm vạch xuất phát: mỗi luồng báo đã tới (countDown) rồi đứng chờ (await).
 *    Luồng thứ N tới thì latch về 0 và cả N luồng cùng chạy; không luồng nào chạy trước khi đủ N luồng sẵn sàng.
 */
public final class Concurrently {

    /** Thời gian chờ tối đa cho cả N tác vụ. */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private Concurrently() {
    }

    /** Một tác vụ; index từ 0 tới n - 1, vd để mỗi tác vụ dùng dữ liệu riêng. */
    @FunctionalInterface
    public interface Task<T> {
        T run(int index) throws Exception;
    }

    /**
     * Chạy task(0) … task(n - 1) cùng một lúc và chờ tất cả xong.
     *
     * @return kết quả theo đúng thứ tự index (không theo thứ tự xong trước / sau)
     * @throws ExecutionException tác vụ ném lỗi (lỗi gốc nằm ở getCause())
     * @throws TimeoutException   chưa xong hết sau 60 giây; các tác vụ còn chạy bị ngắt (interrupt)
     */
    public static <T> List<T> run(int n, Task<T> task)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (n < 1) {
            throw new IllegalArgumentException("n phải >= 1, nhận được " + n);
        }
        CountDownLatch startingLine = new CountDownLatch(n);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    startingLine.countDown();   // đã tới vạch xuất phát
                    startingLine.await();       // đứng chờ tới khi đủ N luồng, rồi cùng chạy
                    return task.run(index);
                }));
            }

            try {
                return collect(futures, System.nanoTime() + TIMEOUT.toNanos());
            } catch (ExecutionException | TimeoutException | InterruptedException e) {
                executor.shutdownNow();   // ngắt các tác vụ còn chạy, để close() không phải chờ chúng
                throw e;
            }
        }
    }

    private static <T> List<T> collect(List<Future<T>> futures, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<T> results = new ArrayList<>(futures.size());
        for (Future<T> future : futures) {
            results.add(future.get(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS));
        }
        return results;
    }
}
