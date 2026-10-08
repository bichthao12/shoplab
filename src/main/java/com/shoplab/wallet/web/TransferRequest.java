package com.shoplab.wallet.web;

import com.shoplab.wallet.internal.TransferCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Body của POST /api/wallets/transfers. */
public record TransferRequest(
        @NotNull @Positive Long fromWalletId,
        @NotNull @Positive Long toWalletId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount
) {
    public TransferCommand toCommand() {
        return new TransferCommand(fromWalletId, toWalletId, amount);
    }
}
