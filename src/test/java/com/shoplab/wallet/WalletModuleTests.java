package com.shoplab.wallet;

import com.shoplab.TestcontainersConfiguration;
import com.shoplab.common.ApiException;
import com.shoplab.idempotency.IdempotencyService;
import com.shoplab.user.UserDirectory;
import com.shoplab.user.UserSummary;
import com.shoplab.user.UserUnavailableException;
import com.shoplab.wallet.internal.DepositCommand;
import com.shoplab.wallet.internal.TransferCommand;
import com.shoplab.wallet.internal.TransferResult;
import com.shoplab.wallet.internal.Wallet;
import com.shoplab.wallet.internal.WalletService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Test riêng module wallet: Spring Modulith chỉ dựng module này (kèm module dùng chung common).
 * API của các module mà wallet phụ thuộc (UserDirectory, IdempotencyService) được thay bằng mock.
 * Mỗi test chạy trong một transaction và rollback ở cuối; user_id bắt đầu bằng 9_000 để không trùng dữ liệu
 * mà integration test để lại trong cùng DB. Vì mọi thao tác nằm chung transaction của test, thay đổi số dư
 * còn trong persistence context cho tới lúc flush: balanceOf flush trước khi đọc bằng SQL.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
@Transactional
class WalletModuleTests {

    @MockitoBean UserDirectory users;
    @MockitoBean IdempotencyService idempotency;   // WalletController cần bean này; test không đi qua HTTP

    @Autowired WalletService wallets;
    @Autowired JdbcClient jdbc;
    @Autowired EntityManager em;

    @Test
    @DisplayName("Tạo ví cho người dùng ACTIVE: số dư 0; người dùng đã có ví → 409 duplicate-wallet")
    void create_emptyWallet_oncePerUser() {
        when(users.requireActiveUser(9_010L)).thenReturn(new UserSummary(9_010L, "A", "a@example.com"));

        Wallet wallet = wallets.create(9_010L);

        assertThat(wallet.getUserId()).isEqualTo(9_010L);
        assertThat(wallet.getBalance()).isEqualByComparingTo("0");
        assertThatThrownBy(() -> wallets.create(9_010L))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getType()).isEqualTo("duplicate-wallet"));
    }

    @Test
    @DisplayName("Người dùng không dùng được (module user báo) → không tạo ví")
    void create_unavailableUser_isRejected() {
        when(users.requireActiveUser(anyLong())).thenThrow(new UserUnavailableException("Người dùng id = 9011 không tồn tại"));

        assertThatThrownBy(() -> wallets.create(9_011L)).isInstanceOf(UserUnavailableException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM wallets WHERE user_id = 9011").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("Nạp tiền và chuyển tiền theo cả hai chiều: trừ ví nguồn, cộng ví đích")
    void depositAndTransfer_inBothDirections() {
        long a = createWallet(9_001, "0.00");
        long b = createWallet(9_002, "100.00");

        assertThat(wallets.deposit(new DepositCommand(a, new BigDecimal("100"))).getBalance()).isEqualByComparingTo("100.00");
        TransferResult result = wallets.transfer(new TransferCommand(a, b, new BigDecimal("10.00")));   // id nhỏ → id lớn
        wallets.transfer(new TransferCommand(b, a, new BigDecimal("30.00")));                            // id lớn → id nhỏ

        assertThat(result.from().getId()).isEqualTo(a);
        assertThat(result.to().getId()).isEqualTo(b);
        assertThat(balanceOf(a)).isEqualByComparingTo("120.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Không đủ tiền → 409 insufficient-balance, không ví nào bị đổi")
    void transfer_insufficientBalance_isRejected() {
        long a = createWallet(9_003, "5.00");
        long b = createWallet(9_004, "0.00");

        assertThatThrownBy(() -> wallets.transfer(new TransferCommand(a, b, new BigDecimal("10.00"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getType()).isEqualTo("insufficient-balance"));
        assertThat(balanceOf(a)).isEqualByComparingTo("5.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Ví không tồn tại → 404 wallet-not-found; chuyển cho chính mình → 422 invalid-transfer")
    void transfer_unknownWalletOrSameWallet_isRejected() {
        long a = createWallet(9_005, "100.00");

        assertThatThrownBy(() -> wallets.transfer(new TransferCommand(a, 999_999_999L, BigDecimal.ONE)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getType()).isEqualTo("wallet-not-found"));
        assertThatThrownBy(() -> wallets.transfer(new TransferCommand(a, a, BigDecimal.ONE)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getType()).isEqualTo("invalid-transfer"));
        assertThat(balanceOf(a)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Command đưa số tiền về 2 chữ số thập phân: 10 và 10.00 là cùng một yêu cầu (cho idempotency)")
    void commands_normalizeAmountScale() {
        assertThat(new TransferCommand(1, 2, new BigDecimal("10"))).isEqualTo(new TransferCommand(1, 2, new BigDecimal("10.00")));
        assertThat(new DepositCommand(1, new BigDecimal("5.5"))).isEqualTo(new DepositCommand(1, new BigDecimal("5.50")));
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
