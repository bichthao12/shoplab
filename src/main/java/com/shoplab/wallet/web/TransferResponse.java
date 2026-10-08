package com.shoplab.wallet.web;

import com.shoplab.wallet.internal.TransferResult;

import java.math.BigDecimal;

/** Kết quả chuyển tiền: số tiền và hai ví sau khi chuyển. */
public record TransferResponse(BigDecimal amount, WalletResponse from, WalletResponse to) {

    public static TransferResponse from(TransferResult r) {
        return new TransferResponse(r.amount(), WalletResponse.from(r.from()), WalletResponse.from(r.to()));
    }
}
