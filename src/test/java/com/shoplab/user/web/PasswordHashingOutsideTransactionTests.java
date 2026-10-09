package com.shoplab.user.web;

import com.shoplab.IntegrationTestBase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Không chờ lâu bên trong transaction: BCrypt cố ý chậm (~80ms), nên UserService.register băm mật khẩu NGOÀI
 * transaction. Trong lúc băm, request không giữ transaction nào và không giữ connection DB nào của pool.
 *
 * PasswordEncoder thật được bọc bằng spy: mỗi lần encode, spy ghi lại có transaction không và pool đang cho mượn
 * bao nhiêu connection, rồi mới băm thật. Chỉ có một request chạy, nên số connection đang mượn là của request đó.
 */
class PasswordHashingOutsideTransactionTests extends IntegrationTestBase {

    record SeenWhileHashing(boolean transactionActive, int activeConnections) {}

    @MockitoSpyBean PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("Đăng ký: lúc băm mật khẩu không có transaction, không giữ connection DB nào; vẫn lưu đúng hash")
    void register_hashesPasswordOutsideTransaction() throws SQLException {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<SeenWhileHashing> seen = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            seen.add(new SeenWhileHashing(TransactionSynchronizationManager.isActualTransactionActive(),
                    pool.getHikariPoolMXBean().getActiveConnections()));
            return call.callRealMethod();
        }).when(passwordEncoder).encode(any());

        assertThat(send("POST", "/api/users", """
                {"email":"a@example.com","fullName":"A","username":"alice","password":"matkhau123"}
                """, null).statusCode()).isEqualTo(201);

        assertThat(seen).containsExactly(new SeenWhileHashing(false, 0));
        String hash = jdbc.sql("SELECT password_hash FROM accounts").query(String.class).single();
        assertThat(passwordEncoder.matches("matkhau123", hash)).isTrue();
    }

    @Test
    @DisplayName("Đăng ký trùng email → 409, không tốn công băm mật khẩu (kiểm tra trùng trước khi băm)")
    void duplicateRegistration_isRejectedBeforeHashing() {
        assertThat(send("POST", "/api/users", """
                {"email":"a@example.com","fullName":"A","username":"alice","password":"matkhau123"}
                """, null).statusCode()).isEqualTo(201);
        List<String> hashed = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            hashed.add("encode");
            return call.callRealMethod();
        }).when(passwordEncoder).encode(any());

        assertProblem(send("POST", "/api/users", """
                {"email":"A@Example.com","fullName":"B","username":"bob","password":"matkhau123"}
                """, null), 409, "duplicate-email");
        assertThat(hashed).isEmpty();
    }
}
