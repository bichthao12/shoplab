package com.shoplab.product.internal;

import com.shoplab.Concurrently;
import com.shoplab.IntegrationTestBase;
import com.shoplab.product.InsufficientStockException;
import com.shoplab.product.ProductInventory;
import com.shoplab.product.internal.NaiveProductInventory.SaveMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 8 người cùng mua 1 cái của một sản phẩm còn 5 cái: so sánh bản ngây thơ (NaiveProductInventory) với bản thật
 * (DefaultProductInventory). Mỗi người mua trong một transaction riêng, cùng xuất phát (Concurrently).
 *
 * Bản ngây thơ được giữ lại giữa bước kiểm tra và bước lưu cho tới khi cả 8 người đã đọc tồn kho,
 * để kết quả luôn như nhau thay vì tuỳ may rủi.
 * Log (logging.level.sql, logging.level.tx) cho thấy 8 câu SELECT chạy trước, rồi 8 câu UPDATE.
 */
class NaiveStockDeductionTests extends IntegrationTestBase {

    private static final int STOCK = 5;
    private static final int BUYERS = 8;

    enum Outcome { BOUGHT, OUT_OF_STOCK, CONFLICT }

    @Autowired ProductRepository repo;
    @Autowired PlatformTransactionManager txManager;

    @Test
    @DisplayName("Ngây thơ, lưu bằng UPDATE stock = ?: cả 8 người mua được, kho chỉ giảm 1 → bán vượt 3, mất 7 lần trừ kho")
    void naive_plainUpdate_oversellsAndLosesUpdates() throws Exception {
        long productId = createProduct("NAIVE-001", 100_000, STOCK);
        ProductInventory naive = new NaiveProductInventory(repo, jdbc, SaveMode.PLAIN_UPDATE, waitUntilAllHaveRead());

        List<Outcome> outcomes = Concurrently.run(BUYERS, i -> buy(naive, "naive-update #" + i, productId));

        assertThat(outcomes).containsOnly(Outcome.BOUGHT);       // 8 người mua, chỉ có 5 cái
        assertThat(stockOf(productId)).isEqualTo(STOCK - 1);     // ai cũng ghi 5 - 1 = 4
    }

    @Test
    @DisplayName("Ngây thơ, lưu qua entity có @Version: không bán vượt, nhưng chỉ 1 người mua được, 7 người lỗi xung đột dù kho còn 4")
    void naive_entityWithVersion_rejectsAllButFirstWriter() throws Exception {
        long productId = createProduct("NAIVE-002", 100_000, STOCK);
        ProductInventory naive = new NaiveProductInventory(repo, jdbc, SaveMode.ENTITY_WITH_VERSION, waitUntilAllHaveRead());

        List<Outcome> outcomes = Concurrently.run(BUYERS, i -> buy(naive, "naive-entity #" + i, productId));

        assertThat(count(outcomes, Outcome.BOUGHT)).isEqualTo(1);
        assertThat(count(outcomes, Outcome.CONFLICT)).isEqualTo(BUYERS - 1);
        assertThat(stockOf(productId)).isEqualTo(STOCK - 1);
    }

    @Test
    @DisplayName("Bản thật (UPDATE ... WHERE stock >= ?, chỉ nhận khi cập nhật đúng 1 dòng): đúng 5 người mua được, 3 người hết hàng, kho về 0")
    void real_conditionalUpdate_sellsExactlyTheStock() throws Exception {
        long productId = createProduct("NAIVE-003", 100_000, STOCK);
        ProductInventory real = new DefaultProductInventory(jdbc);

        List<Outcome> outcomes = Concurrently.run(BUYERS, i -> buy(real, "real #" + i, productId));

        assertThat(count(outcomes, Outcome.BOUGHT)).isEqualTo(STOCK);
        assertThat(count(outcomes, Outcome.OUT_OF_STOCK)).isEqualTo(BUYERS - STOCK);
        assertThat(stockOf(productId)).isZero();
    }

    /** Một người mua 1 cái, trong transaction riêng tên txName (hiện trong log BEGIN / COMMIT / ROLLBACK). */
    private Outcome buy(ProductInventory inventory, String txName, long productId) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setName(txName);
        try {
            tx.executeWithoutResult(status -> inventory.reserveStock(Map.of(productId, 1)));
            return Outcome.BOUGHT;
        } catch (InsufficientStockException e) {
            return Outcome.OUT_OF_STOCK;
        } catch (OptimisticLockingFailureException e) {
            return Outcome.CONFLICT;
        }
    }

    /** Giữ mỗi giao dịch giữa bước kiểm tra và bước lưu, tới khi cả BUYERS giao dịch đã đọc tồn kho. */
    private static Runnable waitUntilAllHaveRead() {
        CountDownLatch allRead = new CountDownLatch(BUYERS);
        return () -> {
            allRead.countDown();
            try {
                if (!allRead.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Không đủ " + BUYERS + " giao dịch cùng đọc tồn kho");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
    }

    private static long count(List<Outcome> outcomes, Outcome outcome) {
        return outcomes.stream().filter(outcome::equals).count();
    }
}
