package com.shoplab.product.internal;

import com.shoplab.IntegrationTestBase;
import com.shoplab.product.ProductInventory;
import com.shoplab.product.internal.NaiveProductInventory.SaveMode;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cùng kịch bản với FlashSaleIntegrationTests (1.000 lượt mua, kho còn 1), nhưng app chạy bản giữ hàng ngây thơ
 * (NaiveProductInventory, lưu bằng UPDATE stock = ?) thay cho bản thật.
 *
 * Bán vượt tối đa bằng số connection của pool: mỗi lượt mua chạy trong một transaction giữ một connection,
 * nên cùng lúc chỉ có ngần ấy lượt đọc được "còn 1 cái" trước khi lượt đầu tiên commit; lượt đến sau đều đọc thấy 0.
 *
 * Để kết quả không tuỳ may rủi, mỗi lượt mua được giữ lại giữa bước kiểm tra và bước lưu tới khi số lượt bằng
 * kích thước pool đều đã đọc kho (trường hợp xấu nhất). Không giữ lại thì số đơn thay đổi theo từng lần chạy:
 * 5 lần chạy thử cho 6, 10, 9, 1, 10 đơn. Có lần chỉ ra 1 đơn, tức là lỗi không lộ ra: vì thế loại lỗi này
 * hay lọt qua kiểm thử bằng tay.
 */
// 1.000 request × ~15 dòng log SQL / transaction thì không đọc được nữa: tắt riêng cho test này
@TestPropertySource(properties = {"logging.level.sql=INFO", "logging.level.tx=INFO"})
@Import(NaiveFlashSaleTests.NaiveInventoryConfig.class)
class NaiveFlashSaleTests extends IntegrationTestBase {

    private static final int BUYERS = 1_000;

    @TestConfiguration(proxyBeanMethods = false)
    static class NaiveInventoryConfig {

        /** OrderService nhận bean này thay cho DefaultProductInventory. */
        @Bean
        @Primary
        ProductInventory naiveInventory(ProductRepository repo, JdbcClient jdbc, DataSource dataSource)
                throws SQLException {
            int poolSize = poolSize(dataSource);
            return new NaiveProductInventory(repo, jdbc, SaveMode.PLAIN_UPDATE, waitUntilRead(poolSize));
        }

        /** Giữ mỗi lượt mua giữa bước kiểm tra và bước lưu, tới khi đủ count lượt đã đọc kho. */
        private static Runnable waitUntilRead(int count) {
            CountDownLatch read = new CountDownLatch(count);
            return () -> {
                read.countDown();
                try {
                    if (!read.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Không đủ " + count + " lượt mua cùng đọc kho");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            };
        }
    }

    @Test
    @DisplayName("Bản ngây thơ: 1.000 lượt mua, kho còn 1 → số đơn bằng số connection của pool (10), kho vẫn báo 0")
    void thousandBuyersOneItem_oversellsUpToPoolSize() throws Exception {
        long productId = createProduct("FLASH-NAIVE-001", 100_000, 1);
        long poolSize = poolSize(dataSource);

        Map<String, Long> outcomes = buyAtOnce(productId, BUYERS);

        assertThat(outcomes).containsExactlyInAnyOrderEntriesOf(Map.of(
                "201", poolSize,
                "409 insufficient-stock", BUYERS - poolSize));
        assertThat(count("orders")).isEqualTo(poolSize);   // bán poolSize cái, chỉ có 1 cái
        assertThat(stockOf(productId)).isZero();           // ai cũng ghi 1 - 1 = 0: kho trông vẫn đúng
    }

    private static int poolSize(DataSource dataSource) throws SQLException {
        return dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
    }
}
