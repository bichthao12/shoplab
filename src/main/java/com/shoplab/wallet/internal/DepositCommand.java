package com.shoplab.wallet.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Nạp amount vào ví walletId. Dạng chuẩn cho idempotency: số tiền đưa về 2 chữ số thập phân. */
public record DepositCommand(long walletId, BigDecimal amount) {

    public DepositCommand {
        amount = amount == null ? null : amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
