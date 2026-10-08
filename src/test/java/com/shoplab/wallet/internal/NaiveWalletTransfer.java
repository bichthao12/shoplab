package com.shoplab.wallet.internal;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * BẢN NGÂY THƠ của chuyển tiền: chỉ có trong test, để tái hiện deadlock. Không dùng trong app.
 *
 * Khoá hai ví theo THỨ TỰ THAM SỐ: ví nguồn trước, ví đích sau.
 * <pre>
 *   A→B:  khoá A ──(chờ)── khoá B ✗ (B đang bị B→A giữ)
 *   B→A:  khoá B ──(chờ)── khoá A ✗ (A đang bị A→B giữ)
 * </pre>
 * Mỗi bên giữ một khoá và chờ khoá bên kia đang giữ: không bên nào đi tiếp được (deadlock).
 * PostgreSQL phát hiện sau deadlock_timeout (mặc định 1 giây), huỷ một trong hai transaction với lỗi 40P01,
 * transaction còn lại lấy được khoá và chạy xong.
 *
 * Phải gọi trong một transaction (khoá giữ tới khi transaction kết thúc).
 */
class NaiveWalletTransfer {

    private final WalletRepository repo;
    private final Duration pauseBetweenLocks;

    /**
     * @param pauseBetweenLocks chờ giữa hai lần khoá, vd 50ms: đủ để giao dịch chạy ngược chiều kịp khoá ví đầu tiên
     *                          của nó, nên deadlock xảy ra chắc chắn thay vì tuỳ may rủi.
     */
    NaiveWalletTransfer(WalletRepository repo, Duration pauseBetweenLocks) {
        this.repo = repo;
        this.pauseBetweenLocks = pauseBetweenLocks;
    }

    void transfer(long fromWalletId, long toWalletId, BigDecimal amount) {
        Wallet from = lock(fromWalletId);   // 1. khoá ví nguồn
        sleep(pauseBetweenLocks);           //    Thread.sleep(50) giữa hai lần khoá
        Wallet to = lock(toWalletId);       // 2. khoá ví đích: chạy ngược chiều thì chờ nhau ở đây
        from.withdraw(amount);
        to.deposit(amount);
    }

    private Wallet lock(long walletId) {
        return repo.findByIdForUpdate(walletId)
                .orElseThrow(() -> new IllegalArgumentException("Không có ví id = " + walletId));
    }

    private static void sleep(Duration pause) {
        try {
            Thread.sleep(pause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
