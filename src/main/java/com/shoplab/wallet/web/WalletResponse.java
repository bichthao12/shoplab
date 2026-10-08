package com.shoplab.wallet.web;

import com.shoplab.wallet.internal.Wallet;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletResponse(
        Long id,
        Long userId,
        BigDecimal balance,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
    public static WalletResponse from(Wallet w) {
        return new WalletResponse(w.getId(), w.getUserId(), w.getBalance(), w.getVersion(),
                w.getCreatedAt(), w.getUpdatedAt());
    }
}
