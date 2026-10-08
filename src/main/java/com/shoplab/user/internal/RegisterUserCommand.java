package com.shoplab.user.internal;

/** Dữ liệu đăng ký: đầu vào của UserService, không phụ thuộc HTTP. Chuẩn hoá và kiểm tra nằm ở User / Account. */
public record RegisterUserCommand(
        String email,
        String fullName,
        String phone,
        String username,
        String password
) {
    /** Không in mật khẩu ra log. */
    @Override
    public String toString() {
        return "RegisterUserCommand[email=" + email + ", fullName=" + fullName + ", phone=" + phone
                + ", username=" + username + ", password=***]";
    }
}
