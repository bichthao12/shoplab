package com.shoplab.user.internal;

/**
 * Thay đổi một phần hồ sơ: trường nào null thì giữ nguyên; phone rỗng = xoá số điện thoại.
 * Đầu vào của UserService, không phụ thuộc HTTP.
 *
 * @param expectedVersion version của hồ sơ mà client đã đọc; khác version hiện tại thì không sửa
 */
public record UpdateProfileCommand(
        String fullName,
        String phone,
        long expectedVersion
) {}
