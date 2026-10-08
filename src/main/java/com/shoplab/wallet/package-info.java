/**
 * Module ví: mỗi người dùng một ví tiền (bảng wallets), số dư không âm.
 *
 * Chưa có API cho module khác. Nội bộ: internal/ (entity Wallet, WalletRepository, WalletService: tạo ví, nạp tiền,
 * chuyển tiền giữa hai ví, khoá hai ví theo thứ tự id tăng dần để không deadlock) và web/ (REST API /api/wallets).
 * Dùng module user qua UserDirectory (chỉ người dùng ACTIVE mới có ví), module idempotency qua IdempotencyService
 * (nạp / chuyển tiền bắt buộc Idempotency-Key).
 * Bản khoá theo thứ tự tham số (gây deadlock) chỉ có trong test: NaiveWalletTransfer.
 */
@ApplicationModule(displayName = "Wallet", allowedDependencies = {"common", "user", "idempotency"})
package com.shoplab.wallet;

import org.springframework.modulith.ApplicationModule;
