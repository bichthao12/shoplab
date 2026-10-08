package com.shoplab.user.web;

import com.shoplab.user.internal.Account;
import com.shoplab.user.internal.AccountStatus;
import com.shoplab.user.internal.User;

import java.time.Instant;

/**
 * Hồ sơ người dùng kèm thông tin tài khoản. Không bao giờ chứa mật khẩu hay hash.
 * version / createdAt / updatedAt là của hồ sơ (bảng users); khoá / mở khoá chỉ đổi account.status.
 */
public record UserResponse(
        Long id,
        String email,
        String fullName,
        String phone,
        AccountResponse account,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {
    public record AccountResponse(String username, AccountStatus status) {

        static AccountResponse from(Account a) {
            return new AccountResponse(a.getUsername(), a.getStatus());
        }
    }

    public static UserResponse from(User u) {
        return new UserResponse(
                u.getId(), u.getEmail(), u.getFullName(), u.getPhone(),
                AccountResponse.from(u.getAccount()),
                u.getVersion(), u.getCreatedAt(), u.getUpdatedAt());
    }
}
