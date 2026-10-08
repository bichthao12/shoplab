package com.shoplab.user;

import com.shoplab.TestcontainersConfiguration;
import com.shoplab.common.ApiException;
import com.shoplab.user.internal.RegisterUserCommand;
import com.shoplab.user.internal.User;
import com.shoplab.user.internal.UserService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test riêng module user: Spring Modulith chỉ dựng module này (kèm module dùng chung common).
 * Kiểm tra những gì không thấy được qua API REST: mật khẩu lưu dạng hash nào, user và account ghi đúng bảng,
 * và API UserDirectory mà module khác (order) dùng.
 * Mỗi test chạy trong một transaction và rollback ở cuối; email / username có tiền tố module
 * để không trùng dữ liệu mà integration test để lại trong cùng DB.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
@Transactional
class UserModuleTests {

    @Autowired UserService users;
    @Autowired UserDirectory directory;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcClient jdbc;
    @Autowired EntityManager em;

    @Test
    @DisplayName("Đăng ký lưu user + account; mật khẩu lưu dạng {bcrypt}, kiểm tra lại được, không lưu bản thô")
    void register_storesBcryptHashNotRawPassword() {
        User user = users.register(new RegisterUserCommand(
                "module.a@example.com", "Nguyễn Văn A", null, "module.alice", "Mật-khẩu-123"));

        Map<String, Object> account = jdbc.sql("""
                        SELECT username, password_hash, status FROM accounts WHERE user_id = :userId
                        """)
                .param("userId", user.getId())
                .query().singleRow();

        String hash = (String) account.get("password_hash");
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain("Mật-khẩu-123");
        assertThat(passwordEncoder.matches("Mật-khẩu-123", hash)).isTrue();
        assertThat(passwordEncoder.matches("mật-khẩu-123", hash)).isFalse();
        assertThat(account).containsEntry("username", "module.alice").containsEntry("status", "ACTIVE");
    }

    @Test
    @DisplayName("Mật khẩu ≤ 72 ký tự nhưng > 72 byte (chữ có dấu) → lỗi validation, không băm, không lưu gì")
    void register_passwordOverBcryptLimit_isRejected() {
        String password = "ệ".repeat(25);   // 25 ký tự, 75 byte UTF-8

        assertThatThrownBy(() -> users.register(new RegisterUserCommand(
                "module.b@example.com", "B", null, "module.bob", password)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getType()).isEqualTo("validation");
                    assertThat(ex.getProperties()).containsKey("errors");
                });
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = 'module.b@example.com'")
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("UserDirectory: tài khoản ACTIVE → trả id, tên, email hiện tại")
    void requireActiveUser_returnsSummary() {
        User user = users.register(new RegisterUserCommand(
                "module.c@example.com", "Lê Văn C", null, "module.carol", "matkhau123"));

        assertThat(directory.requireActiveUser(user.getId()))
                .isEqualTo(new UserSummary(user.getId(), "Lê Văn C", "module.c@example.com"));
    }

    @Test
    @DisplayName("UserDirectory: không tồn tại, bị khoá hay vô hiệu hoá → UserUnavailableException nêu rõ lý do")
    void requireActiveUser_unavailable_isRejected() {
        User user = users.register(new RegisterUserCommand(
                "module.d@example.com", "D", null, "module.dave", "matkhau123"));

        assertThatThrownBy(() -> directory.requireActiveUser(999_999_999L))
                .isInstanceOf(UserUnavailableException.class).hasMessageContaining("không tồn tại");

        users.lockAccount(user.getId());
        assertThatThrownBy(() -> directory.requireActiveUser(user.getId()))
                .isInstanceOf(UserUnavailableException.class).hasMessageContaining("đang bị khoá");

        // Chưa có API vô hiệu hoá: sửa thẳng DB, rồi bỏ bản User đang nằm trong persistence context để đọc lại
        jdbc.sql("UPDATE accounts SET status = 'DISABLED' WHERE user_id = :id").param("id", user.getId()).update();
        em.clear();
        assertThatThrownBy(() -> directory.requireActiveUser(user.getId()))
                .isInstanceOf(UserUnavailableException.class).hasMessageContaining("vô hiệu hoá");
    }
}
