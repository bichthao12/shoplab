/**
 * Module người dùng: hồ sơ (bảng users) và tài khoản đăng nhập 1-1 (bảng accounts).
 *
 * API cho module khác (package này): UserDirectory (tra người dùng đang ACTIVE, vd khi đặt hàng),
 * UserSummary, UserUnavailableException.
 * Nội bộ: internal/ (entity, repository, service, băm mật khẩu) và web/ (REST API /api/users).
 */
@ApplicationModule(displayName = "User", allowedDependencies = "common")
package com.shoplab.user;

import org.springframework.modulith.ApplicationModule;
