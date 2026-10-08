package com.shoplab.wallet.internal;

import com.shoplab.Concurrently;
import com.shoplab.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chuyển tiền A→B và B→A cùng lúc bằng bản khoá theo thứ tự tham số (NaiveWalletTransfer), mỗi lượt chuyển
 * trong một transaction riêng, cùng xuất phát (Concurrently). Chờ 50ms giữa hai lần khoá để cả hai bên chắc chắn
 * đã giữ khoá đầu tiên trước khi xin khoá thứ hai.
 */
class TransferDeadlockTests extends IntegrationTestBase {

    private static final Duration PAUSE_BETWEEN_LOCKS = Duration.ofMillis(50);
    private static final String PG_DEADLOCK_DETECTED = "40P01";

    enum Outcome { TRANSFERRED, DEADLOCK }

    @Autowired WalletRepository repo;
    @Autowired PlatformTransactionManager txManager;

    @Test
    @DisplayName("A→B và B→A cùng lúc, khoá theo thứ tự tham số → deadlock: PostgreSQL huỷ 1 lượt, lượt kia chuyển xong, tổng tiền không đổi")
    void oppositeTransfers_lockingInParameterOrder_deadlock() throws Exception {
        long a = createWallet(1, "100.00");
        long b = createWallet(2, "100.00");
        NaiveWalletTransfer transfer = new NaiveWalletTransfer(repo, PAUSE_BETWEEN_LOCKS);

        long start = System.nanoTime();
        List<Outcome> outcomes = Concurrently.run(2, i -> i == 0
                ? transfer(transfer, "transfer A→B", a, b, "10.00")
                : transfer(transfer, "transfer B→A", b, a, "30.00"));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.TRANSFERRED, Outcome.DEADLOCK);
        // Chỉ lượt thắng được ghi; lượt bị huỷ rollback toàn bộ
        if (outcomes.get(0) == Outcome.TRANSFERRED) {
            assertThat(balanceOf(a)).isEqualByComparingTo("90.00");    // A→B 10
            assertThat(balanceOf(b)).isEqualByComparingTo("110.00");
        } else {
            assertThat(balanceOf(a)).isEqualByComparingTo("130.00");   // B→A 30
            assertThat(balanceOf(b)).isEqualByComparingTo("70.00");
        }
        assertThat(balanceOf(a).add(balanceOf(b))).isEqualByComparingTo("200.00");
        // PostgreSQL chỉ đi tìm deadlock sau khi một bên đã chờ khoá deadlock_timeout (mặc định 1 giây)
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(900));
    }

    /** Một lượt chuyển trong transaction riêng tên txName (hiện trong log BEGIN / COMMIT / ROLLBACK). */
    private Outcome transfer(NaiveWalletTransfer transfer, String txName, long from, long to, String amount) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setName(txName);
        try {
            tx.executeWithoutResult(status -> transfer.transfer(from, to, new BigDecimal(amount)));
            return Outcome.TRANSFERRED;
        } catch (DataAccessException e) {
            if (hasSqlState(e, PG_DEADLOCK_DETECTED)) {
                return Outcome.DEADLOCK;
            }
            throw e;
        }
    }

    private static boolean hasSqlState(Throwable e, String sqlState) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private long createWallet(long userId, String balance) {
        return jdbc.sql("INSERT INTO wallets (user_id, balance) VALUES (:userId, :balance) RETURNING id")
                .param("userId", userId)
                .param("balance", new BigDecimal(balance))
                .query(Long.class)
                .single();
    }

    private BigDecimal balanceOf(long walletId) {
        return jdbc.sql("SELECT balance FROM wallets WHERE id = :id").param("id", walletId)
                .query(BigDecimal.class).single();
    }
}
