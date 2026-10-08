package com.shoplab.user.internal;

import com.shoplab.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.springframework.util.Assert;

import java.util.Locale;

/**
 * Tài khoản đăng nhập của một User (1-1). Chỉ giữ mật khẩu đã băm, không bao giờ giữ mật khẩu thô.
 * Cột last_login_at chưa được map: sẽ dùng khi có chức năng đăng nhập.
 */
@Entity
@Table(name = "accounts")
@SequenceGenerator(sequenceName = "accounts_id_seq", allocationSize = 50)
public class Account extends AuditedEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true, updatable = false)
    private User user;

    @Column(nullable = false, length = 50, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    protected Account() {
        // dành cho JPA
    }

    /** Chỉ User tạo Account, ngay khi đăng ký. */
    Account(User user, String username, String passwordHash) {
        Assert.notNull(user, "user không được null");
        Assert.hasText(passwordHash, "passwordHash không được để trống");
        this.user = user;
        this.username = validUsername(username);
        this.passwordHash = passwordHash;
    }

    public String getUsername() { return username; }
    public AccountStatus getStatus() { return status; }

    // ---------- Thay đổi trạng thái: chỉ gọi được trong package user ----------
    // Khoá khi đã khoá / mở khi đang mở: không đổi gì, coi như thành công (gọi lại nhiều lần vẫn an toàn).

    void lock() {
        ensureNotDisabled();
        status = AccountStatus.LOCKED;
    }

    void unlock() {
        ensureNotDisabled();
        status = AccountStatus.ACTIVE;
    }

    /** Tài khoản đã vô hiệu hoá thì không khoá / mở lại được. */
    private void ensureNotDisabled() {
        if (status == AccountStatus.DISABLED) {
            throw new AccountDisabledException(username);
        }
    }

    // ---------- Quy tắc chuẩn hoá ----------

    /** Username: bỏ khoảng trắng đầu/cuối, đưa về chữ thường (khớp CHECK ck_accounts_username_lowercase). */
    static String normalizeUsername(String username) {
        return username == null ? null : username.strip().toLowerCase(Locale.ROOT);
    }

    private static String validUsername(String username) {
        String normalized = normalizeUsername(username);
        Assert.hasText(normalized, "username không được để trống");
        return normalized;
    }
}
