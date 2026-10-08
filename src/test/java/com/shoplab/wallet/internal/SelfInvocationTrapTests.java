package com.shoplab.wallet.internal;

import com.shoplab.IntegrationTestBase;
import com.shoplab.wallet.internal.BatchTransferService.Outcome;
import jakarta.persistence.TransactionRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bẫy gọi nội bộ của @Transactional (xem BatchTransferService): method @Transactional được gọi từ method khác
 * trong cùng class thì không có transaction.
 *
 * Mỗi test chuyển "lương" từ ví A (100) theo hai lượt:
 *  1. A → ví không tồn tại, 30: lỗi ở bước tìm ví đích, SAU khi đã lưu ví A bị trừ 30 → phải rollback;
 *  2. A → B, 10: thành công.
 * Đúng thì cuối cùng A = 90, B = 110, tổng 200. Log tx (BEGIN / COMMIT / ROLLBACK) cho thấy transaction nào được mở:
 * qua proxy chỉ có một cặp BEGIN / ROLLBACK BatchTransferService.transfer; gọi nội bộ thì không có BEGIN nào cho
 * transfer, mỗi lời gọi repository tự BEGIN / COMMIT (SimpleJpaRepository.save ...).
 */
@Import(BatchTransferService.class)
class SelfInvocationTrapTests extends IntegrationTestBase {

    private static final long MISSING_WALLET = 999_999_999L;

    @Autowired BatchTransferService batch;   // proxy Spring tạo vì class có @Transactional
    @Autowired WalletService walletService;

    private long a;
    private long b;

    @BeforeEach
    void createWallets() {
        a = createWallet(1, "100.00");
        b = createWallet(2, "100.00");
    }

    @Test
    @DisplayName("Gọi transfer từ bên ngoài (qua proxy) → có transaction: lỗi ở bước tìm ví đích thì rollback, A không bị trừ")
    void transfer_calledThroughProxy_rollsBack() {
        assertThat(AopUtils.isAopProxy(batch)).isTrue();

        assertThatThrownBy(() -> batch.transfer(new TransferCommand(a, MISSING_WALLET, new BigDecimal("30"))))
                .isInstanceOf(WalletNotFoundException.class);

        assertThat(batch.lastTransferHadTransaction()).isTrue();
        assertThat(balanceOf(a)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("BẪY: transferAll gọi this.transfer → không có transaction; lượt lỗi vẫn trừ 30 của A mà không ai nhận → mất 30")
    void transferAll_selfInvocation_losesMoney() {
        List<Outcome> outcomes = batch.transferAll(payroll());

        assertThat(outcomes).containsExactly(Outcome.FAILED, Outcome.TRANSFERRED);
        assertThat(batch.lastTransferHadTransaction()).isFalse();
        assertThat(balanceOf(a)).isEqualByComparingTo("60.00");    // 100 - 30 (lượt lỗi, không rollback) - 10
        assertThat(balanceOf(b)).isEqualByComparingTo("110.00");
        assertThat(balanceOf(a).add(balanceOf(b))).isEqualByComparingTo("170.00");   // 30 biến mất
    }

    @Test
    @DisplayName("SỬA: mỗi lượt chạy trong transaction mở bằng TransactionTemplate → lượt lỗi rollback, lượt kia vẫn chuyển, tổng giữ 200")
    void transferAllEachInOwnTransaction_keepsMoney() {
        List<Outcome> outcomes = batch.transferAllEachInOwnTransaction(payroll());

        assertThat(outcomes).containsExactly(Outcome.FAILED, Outcome.TRANSFERRED);
        assertThat(balanceOf(a)).isEqualByComparingTo("90.00");    // lượt lỗi đã rollback: chỉ trừ 10
        assertThat(balanceOf(b)).isEqualByComparingTo("110.00");
        assertThat(batch.lastTransferHadTransaction()).isTrue();
    }

    @Test
    @DisplayName("Code thật (WalletService.transfer) bị gọi không qua proxy → lỗi ngay ở câu khoá FOR UPDATE (cần transaction), không ví nào bị đổi")
    void realTransferWithoutProxy_failsFastOnLockQuery() {
        // Object thật bên trong proxy: gọi vào nó giống hệt this.transfer(...) gọi từ một method khác của WalletService
        WalletService target = AopTestUtils.getTargetObject(walletService);
        assertThat(AopUtils.isAopProxy(target)).isFalse();

        assertThatThrownBy(() -> target.transfer(new TransferCommand(a, b, new BigDecimal("10"))))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(TransactionRequiredException.class);

        // May mắn chứ không phải được thiết kế để chặn: đổi sang findById + save (như BatchTransferService) là mất tiền
        assertThat(balanceOf(a)).isEqualByComparingTo("100.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("100.00");
    }

    private List<TransferCommand> payroll() {
        return List.of(
                new TransferCommand(a, MISSING_WALLET, new BigDecimal("30")),
                new TransferCommand(a, b, new BigDecimal("10")));
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
