package com.shoplab.wallet.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Body của POST /api/wallets: tạo ví rỗng cho người dùng. */
public record CreateWalletRequest(@NotNull @Positive Long userId) {}
