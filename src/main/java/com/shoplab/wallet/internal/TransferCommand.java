package com.shoplab.wallet.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Chuyển amount từ ví fromWalletId sang ví toWalletId. Đầu vào của WalletService, đồng thời là dạng chuẩn để
 * idempotency so sánh hai request: số tiền đưa về 2 chữ số thập phân (10 và 10.00 là một).
 */
public record TransferCommand(long fromWalletId, long toWalletId, BigDecimal amount) {

    public TransferCommand {
        amount = amount == null ? null : amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
