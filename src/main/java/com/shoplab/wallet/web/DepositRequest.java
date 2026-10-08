package com.shoplab.wallet.web;

import com.shoplab.wallet.internal.DepositCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Body của POST /api/wallets/{id}/deposits. */
public record DepositRequest(
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount
) {
    public DepositCommand toCommand(long walletId) {
        return new DepositCommand(walletId, amount);
    }
}
