/**
 * Module ví: mỗi người dùng một ví tiền (bảng wallets), số dư không âm.
 *
 * Chưa có API cho module khác và chưa có REST API. Nội bộ: internal/ (entity Wallet, WalletRepository,
 * WalletService chuyển tiền giữa hai ví, khoá hai ví theo thứ tự id tăng dần để không deadlock).
 * Bản khoá theo thứ tự tham số (gây deadlock) chỉ có trong test: NaiveWalletTransfer.
 */
@ApplicationModule(displayName = "Wallet", allowedDependencies = "common")
package com.shoplab.wallet;

import org.springframework.modulith.ApplicationModule;
