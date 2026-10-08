package com.shoplab.wallet.internal;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * BẪY GỌI NỘI BỘ của @Transactional: chỉ có trong test (SelfInvocationTrapTests), để tái hiện. Không dùng trong app.
 *
 * Chuyển tiền hàng loạt (vd trả lương): mỗi lượt chuyển phải là một transaction riêng; lượt nào lỗi thì rollback
 * lượt đó, các lượt khác vẫn chạy. transferAll gọi this.transfer(...) và tưởng @Transactional của transfer có hiệu lực.
 *
 * Nhưng @Transactional chạy nhờ PROXY: Spring đưa cho nơi khác một object bọc ngoài BatchTransferService, mở
 * transaction rồi mới gọi vào method thật. this.transfer(...) là lời gọi từ bên trong object thật, không qua proxy,
 * nên không có transaction nào được mở:
 * <pre>
 *   bên ngoài ──► proxy ──(BEGIN)──► transfer()           có transaction
 *   bên ngoài ──► proxy ──► transferAll() ──this.──► transfer()   KHÔNG có transaction
 * </pre>
 * Khi không có transaction, mỗi lời gọi repository (findById, save) tự chạy trong transaction riêng của nó và
 * commit ngay. transfer lưu ví nguồn (đã trừ tiền) trước, rồi mới tìm ví đích: ví đích không tồn tại thì lỗi,
 * nhưng phần trừ tiền đã commit, không có gì để rollback → tiền biến mất.
 */
class BatchTransferService {

    enum Outcome { TRANSFERRED, FAILED }

    private final WalletRepository repo;
    private final TransactionTemplate tx;

    /** transfer ghi lại lần gọi gần nhất có chạy trong transaction không, để test kiểm tra. */
    private volatile boolean lastTransferHadTransaction;

    BatchTransferService(WalletRepository repo, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setName("BatchTransferService.transfer (TransactionTemplate)");   // tên hiện trong log BEGIN / COMMIT
    }

    /** Một lượt chuyển: trừ ví nguồn, lưu; tìm ví đích, cộng, lưu. Lỗi ở bước nào thì cả lượt phải rollback. */
    @Transactional
    public void transfer(TransferCommand command) {
        lastTransferHadTransaction = TransactionSynchronizationManager.isActualTransactionActive();

        Wallet from = repo.findById(command.fromWalletId())
                .orElseThrow(() -> new WalletNotFoundException(command.fromWalletId()));
        from.withdraw(command.amount());
        repo.save(from);                                                  // ① trừ tiền ví nguồn

        Wallet to = repo.findById(command.toWalletId())                   // ② ví đích không tồn tại → lỗi
                .orElseThrow(() -> new WalletNotFoundException(command.toWalletId()));
        to.deposit(command.amount());
        repo.save(to);                                                    // ③ cộng tiền ví đích
    }

    /** BẪY: this.transfer(...) không đi qua proxy → @Transactional của transfer bị bỏ qua. */
    public List<Outcome> transferAll(List<TransferCommand> commands) {
        return runEach(commands, this::transfer);
    }

    /** SỬA: tự mở transaction cho từng lượt bằng TransactionTemplate, không trông vào @Transactional của transfer. */
    public List<Outcome> transferAllEachInOwnTransaction(List<TransferCommand> commands) {
        return runEach(commands, command -> tx.executeWithoutResult(status -> transfer(command)));
    }

    boolean lastTransferHadTransaction() {
        return lastTransferHadTransaction;
    }

    private static List<Outcome> runEach(List<TransferCommand> commands, Consumer<TransferCommand> transfer) {
        List<Outcome> outcomes = new ArrayList<>();
        for (TransferCommand command : commands) {
            try {
                transfer.accept(command);
                outcomes.add(Outcome.TRANSFERRED);
            } catch (RuntimeException e) {
                outcomes.add(Outcome.FAILED);   // lượt lỗi không chặn các lượt sau
            }
        }
        return outcomes;
    }
}
