package com.shoplab.user.internal;

import com.shoplab.common.AuditedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.springframework.util.Assert;

import java.util.Locale;

/**
 * Người dùng: hồ sơ cá nhân, kèm đúng một tài khoản đăng nhập (Account).
 * User là gốc: Account được tạo, lưu và nạp cùng User, không có repository riêng.
 */
@Entity
@Table(name = "users")
@SequenceGenerator(sequenceName = "users_id_seq", allocationSize = 50)
public class User extends AuditedEntity {

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String fullName;

    @Column(length = 20)
    private String phone;

    /** Khoá ngoại nằm ở accounts.user_id. Nạp luôn cùng User nên đọc được cả sau khi transaction kết thúc. */
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, optional = false)
    private Account account;

    protected User() {
        // dành cho JPA
    }

    /**
     * Chỉ tạo được trong package user (qua UserService).
     * Chỉ nhận mật khẩu đã băm; mật khẩu thô trong command không được dùng tới.
     */
    User(RegisterUserCommand command, String passwordHash) {
        this.email = validEmail(command.email());
        this.fullName = validFullName(command.fullName());
        this.phone = normalizePhone(command.phone());
        this.account = new Account(this, command.username(), passwordHash);
    }

    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getPhone() { return phone; }
    public Account getAccount() { return account; }

    // ---------- Thay đổi dữ liệu: chỉ gọi được trong package user ----------

    void changeFullName(String fullName) { this.fullName = validFullName(fullName); }

    /** Chuỗi rỗng = xoá số điện thoại. */
    void changePhone(String phone)       { this.phone = normalizePhone(phone); }

    // ---------- Quy tắc chuẩn hoá: định nghĩa MỘT lần, dùng được cả khi chưa có entity ----------

    /** Email: bỏ khoảng trắng đầu/cuối, đưa về chữ thường (khớp CHECK ck_users_email_lowercase). */
    static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    /** Số điện thoại: bỏ khoảng trắng đầu/cuối; rỗng thì lưu null. */
    static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String stripped = phone.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    // ---------- Kiểm tra: request đã qua validation, nên lỗi ở đây là lỗi lập trình ----------

    private static String validEmail(String email) {
        String normalized = normalizeEmail(email);
        Assert.hasText(normalized, "email không được để trống");
        return normalized;
    }

    private static String validFullName(String fullName) {
        Assert.hasText(fullName, "fullName không được để trống");
        return fullName.strip();
    }
}
