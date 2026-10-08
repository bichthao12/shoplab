package com.shoplab.wallet.internal;

import com.shoplab.common.DbConstraints;
import com.shoplab.user.UserDirectory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ví: tạo ví, nạp tiền, chuyển tiền giữa hai ví. Nhận command và trả entity, không biết gì về HTTP.
 * Mọi thay đổi số dư đều khoá dòng ví (SELECT ... FOR UPDATE), nên các lần nạp / chuyển cùng lúc trên một ví
 * chạy lần lượt; khi khoá nhiều ví thì khoá theo thứ tự id tăng dần.
 */
@Service
@Transactional(readOnly = true)
public class WalletService {

    private static final String UK_WALLETS_USER = "uk_wallets_user";

    private final WalletRepository repo;
    private final UserDirectory users;

    public WalletService(WalletRepository repo, UserDirectory users) {
        this.repo = repo;
        this.users = users;
    }

    /**
     * Tạo ví rỗng cho người dùng đang ACTIVE.
     *
     * @throws com.shoplab.user.UserUnavailableException người dùng không tồn tại hoặc tài khoản không ACTIVE
     * @throws DuplicateWalletException                  người dùng đã có ví
     */
    @Transactional
    public Wallet create(long userId) {
        users.requireActiveUser(userId);
        if (repo.existsByUserId(userId)) {
            throw new DuplicateWalletException(userId);
        }
        try {
            return repo.saveAndFlush(new Wallet(userId));
        } catch (DataIntegrityViolationException ex) {
            // Hai request tạo ví cho cùng người dùng lọt qua bước kiểm tra trước: DB chặn bằng uk_wallets_user
            if (DbConstraints.isViolated(ex, UK_WALLETS_USER)) {
                throw new DuplicateWalletException(userId);
            }
            throw ex;
        }
    }

    public Wallet getById(long walletId) {
        return repo.findById(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    /** Nạp tiền vào ví. Khoá ví trước, nên nạp / chuyển cùng lúc trên ví này chạy lần lượt. */
    @Transactional
    public Wallet deposit(DepositCommand command) {
        Wallet wallet = lock(command.walletId());
        wallet.deposit(command.amount());
        return repo.saveAndFlush(wallet);   // flush: version, updatedAt trả về là giá trị mới
    }

    /**
     * Chuyển tiền giữa hai ví trong một transaction.
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
    public TransferResult transfer(TransferCommand command) {
        long fromWalletId = command.fromWalletId();
        long toWalletId = command.toWalletId();
        if (fromWalletId == toWalletId) {
            throw new InvalidTransferException("Không chuyển tiền cho chính ví id = " + fromWalletId);
        }
        long firstId = Math.min(fromWalletId, toWalletId);
        Wallet first = lock(firstId);                                 // id nhỏ trước
        Wallet second = lock(Math.max(fromWalletId, toWalletId));     // id lớn sau

        Wallet from = firstId == fromWalletId ? first : second;
        Wallet to = firstId == fromWalletId ? second : first;
        from.withdraw(command.amount());
        to.deposit(command.amount());
        repo.flush();   // version, updatedAt trả về là giá trị mới
        return new TransferResult(from, to, command.amount());
    }

    private Wallet lock(long walletId) {
        return repo.findByIdForUpdate(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
    }
}
