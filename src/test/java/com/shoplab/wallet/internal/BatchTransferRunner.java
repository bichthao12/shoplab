package com.shoplab.wallet.internal;

import com.shoplab.wallet.internal.BatchTransferService.Outcome;

import java.util.List;

/**
 * SỬA bẫy gọi nội bộ bằng cách TÁCH SANG BEAN KHÁC (chỉ có trong test, xem SelfInvocationTrapTests).
 *
 * Vòng lặp chuyển hàng loạt nằm ở bean này; transfer (có @Transactional) vẫn ở BatchTransferService. Spring tiêm
 * vào đây proxy của BatchTransferService, nên transfers.transfer(...) là lời gọi từ bên ngoài, đi qua proxy:
 * mỗi lượt chạy trong transaction riêng, lượt lỗi rollback. Đây là cách sửa nên dùng: không cần nhớ quy tắc gì,
 * code nhìn vào là thấy lời gọi đi sang bean khác.
 */
class BatchTransferRunner {

    private final BatchTransferService transfers;

    BatchTransferRunner(BatchTransferService transfers) {
        this.transfers = transfers;
    }

    List<Outcome> transferAll(List<TransferCommand> commands) {
        return BatchTransferService.runEach(commands, transfers::transfer);
    }
}
