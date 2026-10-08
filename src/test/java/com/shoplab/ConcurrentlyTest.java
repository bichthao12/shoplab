package com.shoplab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit test cho hàm tiện ích Concurrently (không cần Spring, không cần DB). */
class ConcurrentlyTest {

    @Test
    @DisplayName("Kết quả theo đúng thứ tự index; mỗi tác vụ chạy trên một virtual thread")
    void returnsResultsInIndexOrder_onVirtualThreads() throws Exception {
        List<String> results = Concurrently.run(4, i -> i + ":" + Thread.currentThread().isVirtual());

        assertThat(results).containsExactly("0:true", "1:true", "2:true", "3:true");
    }

    @Test
    @DisplayName("N tác vụ chạy cùng một lúc: tác vụ nào cũng chờ được tới khi cả N tác vụ đều đang chạy")
    void allTasksRunAtTheSameTime() throws Exception {
        int n = 100;
        CountDownLatch allRunning = new CountDownLatch(n);

        List<Boolean> sawAllRunning = Concurrently.run(n, i -> {
            allRunning.countDown();
            return allRunning.await(5, TimeUnit.SECONDS);   // chạy lần lượt thì không bao giờ đủ N → hết giờ, false
        });

        assertThat(sawAllRunning).hasSize(n).containsOnly(true);
    }

    @Test
    @DisplayName("Tác vụ ném lỗi → ExecutionException mang lỗi gốc")
    void taskFailure_isPropagated() {
        assertThatThrownBy(() -> Concurrently.run(3, i -> {
            if (i == 1) {
                throw new IllegalStateException("tác vụ 1 hỏng");
            }
            return i;
        }))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("tác vụ 1 hỏng");
    }
}
