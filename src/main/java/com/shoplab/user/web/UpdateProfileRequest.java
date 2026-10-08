package com.shoplab.user.web;

import com.shoplab.common.ValidationPatterns;
import com.shoplab.user.internal.UpdateProfileCommand;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH hồ sơ: trường nào null (không gửi) thì giữ nguyên.
 * phone = "" để xoá số điện thoại. Email và username chưa đổi được qua API này.
 */
public record UpdateProfileRequest(
        @Size(max = 255) @Pattern(regexp = ValidationPatterns.NOT_BLANK, message = "không được để trống")
        String fullName,
        @Pattern(regexp = "(" + ValidationPatterns.PHONE + ")?",
                message = "số điện thoại gồm 8–15 chữ số, có thể bắt đầu bằng +, hoặc rỗng để xoá")
        String phone
) {

    /** Chuyển sang đầu vào của UserService (gọi sau khi đã qua validation). */
    public UpdateProfileCommand toCommand() {
        return new UpdateProfileCommand(fullName, phone);
    }
}
