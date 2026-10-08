/**
 * Module ví: mỗi người dùng một ví tiền (bảng wallets), số dư không âm.
 *
 * Chưa có API cho module khác và chưa có REST API. Nội bộ: internal/ (entity Wallet, WalletRepository).
 * Chuyển tiền giữa hai ví: hiện mới có bản khoá theo thứ tự tham số trong test (NaiveWalletTransfer),
 * dùng để tái hiện deadlock.
 */
@ApplicationModule(displayName = "Wallet", allowedDependencies = "common")
package com.shoplab.wallet;

import org.springframework.modulith.ApplicationModule;
