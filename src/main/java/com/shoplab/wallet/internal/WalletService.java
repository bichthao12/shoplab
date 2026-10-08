package com.shoplab.wallet.internal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Chuyển tiền giữa hai ví. Nội bộ module wallet (chưa có REST API, chưa có API cho module khác). */
@Service
public class WalletService {

    private final WalletRepository repo;

    public WalletService(WalletRepository repo) {
        this.repo = repo;
    }

    /**
     * Chuyển amount từ ví fromWalletId sang ví toWalletId trong một transaction.
     *
     * Khoá hai ví theo thứ tự id TĂNG DẦN, không theo chiều chuyển. A→B và B→A đều khoá ví id nhỏ trước,
     * nên lượt đến sau chờ ngay ở khoá đầu tiên cho tới khi lượt trước commit: không bao giờ có cảnh mỗi bên
     * giữ một khoá rồi chờ khoá bên kia (deadlock). Chỗ nào khác khoá nhiều ví cùng lúc cũng phải theo thứ tự này.
     *
     * @throws InvalidTransferException      chuyển cho chính ví nguồn
     * @throws WalletNotFoundException       không có ví
     * @throws InsufficientBalanceException  ví nguồn không đủ tiền (không ví nào bị đổi)
     */
    @Transactional
    public void transfer(long fromWalletId, long toWalletId, BigDecimal amount) {
        if (fromWalletId == toWalletId) {
            throw new InvalidTransferException("Không chuyển tiền cho chính ví id = " + fromWalletId);
        }
        long firstId = Math.min(fromWalletId, toWalletId);
        Wallet first = lock(firstId);                                 // id nhỏ trước
        Wallet second = lock(Math.max(fromWalletId, toWalletId));     // id lớn sau

        Wallet from = firstId == fromWalletId ? first : second;
        Wallet to = firstId == fromWalletId ? second : first;
        from.withdraw(amount);
        to.deposit(amount);
    }

    private Wallet lock(long walletId) {
        return repo.findByIdForUpdate(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
    }
}
