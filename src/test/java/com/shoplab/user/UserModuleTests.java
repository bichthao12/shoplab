package com.shoplab.user;

import com.shoplab.TestcontainersConfiguration;
import com.shoplab.common.ApiException;
import com.shoplab.user.internal.RegisterUserCommand;
import com.shoplab.user.internal.User;
import com.shoplab.user.internal.UserService;
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
 * Kiểm tra những gì không thấy được qua API: mật khẩu lưu dạng hash nào, user và account ghi đúng bảng.
 * Mỗi test chạy trong một transaction và rollback ở cuối; email / username có tiền tố module
 * để không trùng dữ liệu mà integration test để lại trong cùng DB.
 */
@ApplicationModuleTest
@Import(TestcontainersConfiguration.class)
@Transactional
class UserModuleTests {

    @Autowired UserService users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcClient jdbc;

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
}
