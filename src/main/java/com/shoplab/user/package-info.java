/**
 * Module người dùng: hồ sơ (bảng users) và tài khoản đăng nhập 1-1 (bảng accounts).
 *
 * Chưa có API cho module khác: đơn hàng vẫn tự lưu tên / email khách. Khi cần gắn đơn với người dùng,
 * thêm interface vào package này (giống ProductInventory) thay vì để module khác gọi vào internal.
 * Nội bộ: internal/ (entity, repository, service, băm mật khẩu) và web/ (REST API /api/users).
 */
@ApplicationModule(displayName = "User", allowedDependencies = "common")
package com.shoplab.user;

import org.springframework.modulith.ApplicationModule;
