package com.shoplab.user.web;

import com.shoplab.common.ValidationPatterns;
import com.shoplab.user.internal.RegisterUserCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Đăng ký: tạo người dùng kèm tài khoản đăng nhập.
 * password tối đa 72 ký tự ở đây; giới hạn 72 byte của BCrypt (chữ có dấu chiếm 2–3 byte) do UserService kiểm tra.
 */
public record RegisterUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 255) String fullName,
        @Pattern(regexp = ValidationPatterns.PHONE, message = "số điện thoại gồm 8–15 chữ số, có thể bắt đầu bằng +")
        String phone,           // tuỳ chọn
        @NotBlank @Size(min = 3, max = 50)
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "chỉ gồm chữ cái không dấu, chữ số và . _ -")
        String username,
        @NotBlank @Size(min = 8, max = 72) String password
) {

    /** Chuyển sang đầu vào của UserService (gọi sau khi đã qua validation). */
    public RegisterUserCommand toCommand() {
        return new RegisterUserCommand(email, fullName, phone, username, password);
    }

    /** Không in mật khẩu ra log. */
    @Override
    public String toString() {
        return "RegisterUserRequest[email=" + email + ", fullName=" + fullName + ", phone=" + phone
                + ", username=" + username + ", password=***]";
    }
}
