package com.shoplab.user.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit test cho quy tắc nằm trong entity User / Account (không cần Spring, không cần DB). */
class UserTest {

    @Test
    @DisplayName("Đăng ký: email, username về chữ thường; bỏ khoảng trắng; phone rỗng lưu null; tài khoản ACTIVE")
    void constructor_normalizesInputAndCreatesActiveAccount() {
        User u = new User(new RegisterUserCommand(
                " A.Nguyen@Example.COM ", " Nguyễn Văn A ", "  ", " Alice.N ", "matkhau123"), "{bcrypt}hash");

        assertThat(u.getEmail()).isEqualTo("a.nguyen@example.com");
        assertThat(u.getFullName()).isEqualTo("Nguyễn Văn A");
        assertThat(u.getPhone()).isNull();
        assertThat(u.getAccount().getUsername()).isEqualTo("alice.n");
        assertThat(u.getAccount().getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Sửa hồ sơ: tên bỏ khoảng trắng, không cho để trống; phone rỗng = xoá")
    void changeProfile_normalizesAndRejectsBlankName() {
        User u = user();

        u.changeFullName("  Trần Thị B ");
        u.changePhone(" +84912345678 ");
        assertThat(u.getFullName()).isEqualTo("Trần Thị B");
        assertThat(u.getPhone()).isEqualTo("+84912345678");

        u.changePhone("");
        assertThat(u.getPhone()).isNull();

        assertThatThrownBy(() -> u.changeFullName(" \t ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(u.getFullName()).isEqualTo("Trần Thị B");
    }

    @Test
    @DisplayName("Khoá / mở khoá; làm lại lần nữa không đổi gì và không lỗi")
    void lockAndUnlock_areIdempotent() {
        Account account = user().getAccount();

        account.lock();
        account.lock();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.LOCKED);

        account.unlock();
        account.unlock();
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("Tài khoản đã vô hiệu hoá → không khoá / mở được, trạng thái giữ nguyên")
    void disabledAccount_cannotBeLockedOrUnlocked() {
        Account account = user().getAccount();
        ReflectionTestUtils.setField(account, "status", AccountStatus.DISABLED);   // chưa có API vô hiệu hoá

        assertThatThrownBy(account::lock).isInstanceOf(AccountDisabledException.class);
        assertThatThrownBy(account::unlock).isInstanceOf(AccountDisabledException.class);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.DISABLED);
    }

    @Test
    @DisplayName("Thiếu hash mật khẩu hoặc username trống → lỗi lập trình, không tạo được")
    void invariants_areEnforced() {
        assertThatThrownBy(() -> new User(new RegisterUserCommand(
                "a@example.com", "A", null, "alice", "matkhau123"), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new User(new RegisterUserCommand(
                "a@example.com", "A", null, " ", "matkhau123"), "{bcrypt}hash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("toString của command không lộ mật khẩu")
    void command_toStringHidesPassword() {
        assertThat(new RegisterUserCommand("a@example.com", "A", null, "alice", "bi-mat-123").toString())
                .doesNotContain("bi-mat-123")
                .contains("password=***");
    }

    private static User user() {
        return new User(new RegisterUserCommand(
                "a@example.com", "Nguyễn Văn A", null, "alice", "matkhau123"), "{bcrypt}hash");
    }
}
