package com.shoplab.wallet;

import com.shoplab.TestcontainersConfiguration;
import com.shoplab.common.ApiException;
import com.shoplab.wallet.internal.WalletService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test riêng module wallet: Spring Modulith chỉ dựng module này (kèm module dùng chung common).
 * Mỗi test chạy trong một transaction và rollback ở cuối; user_id bắt đầu bằng 9_000 để không trùng dữ liệu
 * mà integration test để lại trong cùng DB. Vì mọi lần chuyển nằm chung transaction của test, thay đổi số dư
 * còn trong persistence context cho tới lúc flush: balanceOf flush trước khi đọc bằng SQL.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
@Transactional
class WalletModuleTests {

    @Autowired WalletService wallets;
    @Autowired JdbcClient jdbc;
    @Autowired EntityManager em;

    @Test
    @DisplayName("Chuyển tiền: trừ ví nguồn, cộng ví đích, theo cả hai chiều")
    void transfer_movesMoneyInBothDirections() {
        long a = createWallet(9_001, "100.00");
        long b = createWallet(9_002, "100.00");

        wallets.transfer(a, b, new BigDecimal("10.00"));   // id nhỏ → id lớn
        wallets.transfer(b, a, new BigDecimal("30.00"));   // id lớn → id nhỏ

        assertThat(balanceOf(a)).isEqualByComparingTo("120.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Không đủ tiền → 409 insufficient-balance, không ví nào bị đổi")
    void transfer_insufficientBalance_isRejected() {
        long a = createWallet(9_003, "5.00");
        long b = createWallet(9_004, "0.00");

        assertThatThrownBy(() -> wallets.transfer(a, b, new BigDecimal("10.00")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getType()).isEqualTo("insufficient-balance"));
        assertThat(balanceOf(a)).isEqualByComparingTo("5.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Ví không tồn tại → 404 wallet-not-found; chuyển cho chính mình → 422 invalid-transfer")
    void transfer_unknownWalletOrSameWallet_isRejected() {
        long a = createWallet(9_005, "100.00");

        assertThatThrownBy(() -> wallets.transfer(a, 999_999_999L, BigDecimal.ONE))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getType()).isEqualTo("wallet-not-found"));
        assertThatThrownBy(() -> wallets.transfer(a, a, BigDecimal.ONE))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getType()).isEqualTo("invalid-transfer"));
        assertThat(balanceOf(a)).isEqualByComparingTo("100.00");
    }

    private long createWallet(long userId, String balance) {
        return jdbc.sql("INSERT INTO wallets (user_id, balance) VALUES (:userId, :balance) RETURNING id")
                .param("userId", userId)
                .param("balance", new BigDecimal(balance))
                .query(Long.class)
                .single();
    }

    private BigDecimal balanceOf(long walletId) {
        em.flush();
        return jdbc.sql("SELECT balance FROM wallets WHERE id = :id").param("id", walletId).query(BigDecimal.class).single();
    }
}
