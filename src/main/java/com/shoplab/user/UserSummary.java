package com.shoplab.user;

/**
 * Thông tin người dùng TẠI THỜI ĐIỂM tra cứu, để module khác chụp lại (vd tên, email người đặt đơn):
 * sửa hồ sơ sau đó không làm đổi dữ liệu đã chụp.
 */
public record UserSummary(
        long id,
        String fullName,
        String email
) {}
