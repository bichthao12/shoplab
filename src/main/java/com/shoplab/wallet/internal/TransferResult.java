package com.shoplab.wallet.internal;

import java.math.BigDecimal;

/** Kết quả chuyển tiền: hai ví sau khi đã trừ / cộng. */
public record TransferResult(Wallet from, Wallet to, BigDecimal amount) {}
