package com.shoplab.user.web;

import com.shoplab.Concurrently;
import com.shoplab.IntegrationTestBase;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test cho API người dùng:
 *  - đăng ký tạo user + account, chuẩn hoá dữ liệu, không bao giờ trả mật khẩu,
 *  - trùng email / username → 409 (kể cả khi DB mới chặn được),
 *  - sửa hồ sơ, khoá / mở khoá tài khoản.
 */
class UserApiIntegrationTests extends IntegrationTestBase {

    // =====================================================================
    // 1. Đăng ký
    // =====================================================================

    @Test
    @DisplayName("POST → 201 + Location; email, username về chữ thường; tên bỏ khoảng trắng; tài khoản ACTIVE; không trả mật khẩu")
    void register_createsUserAndAccount() {
        HttpResponse<String> created = send("POST", "/api/users", """
                {"email":"A.Nguyen@Example.com","fullName":" Nguyễn Văn A ","phone":"0912345678",
                 "username":"Alice.N","password":"matkhau-bi-mat"}
                """, null);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).doesNotContain("matkhau-bi-mat").doesNotContainIgnoringCase("password");

        String path = URI.create(created.headers().firstValue("Location").orElseThrow()).getPath();
        Map<String, Object> u = json(send("GET", path, null, null));

        assertThat(u.get("email")).isEqualTo("a.nguyen@example.com");
        assertThat(u.get("fullName")).isEqualTo("Nguyễn Văn A");
        assertThat(u.get("phone")).isEqualTo("0912345678");
        assertThat(u.get("account")).isEqualTo(Map.of("username", "alice.n", "status", "ACTIVE"));
        assertThat(u.get("version")).isEqualTo(0);
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("accounts")).isEqualTo(1);
    }

    @Test
    @DisplayName("Email hoặc username đã có (khác hoa/thường) → 409, không tạo thêm gì")
    void register_duplicateEmailOrUsername_returns409() {
        assertThat(register("a@example.com", "alice").statusCode()).isEqualTo(201);

        assertProblem(register("A@Example.com", "bob"), 409, "duplicate-email");
        assertProblem(register("b@example.com", "ALICE"), 409, "duplicate-username");
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("accounts")).isEqualTo(1);
    }

    @Test
    @DisplayName("Body sai → 400 kèm lỗi theo từng trường")
    void register_invalidBody_returns400WithFieldErrors() {
        HttpResponse<String> r = send("POST", "/api/users", """
                {"email":"khong-phai-email","fullName":" ","phone":"12-34",
                 "username":"có dấu","password":"ngan"}
                """, null);

        assertProblem(r, 400, "validation");
        assertThat(json(r).get("errors")).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("email", "fullName", "phone", "username", "password");
        assertThat(count("users")).isZero();
    }

    @Test
    @DisplayName("Mật khẩu ≤ 72 ký tự nhưng > 72 byte → 400 validation với errors.password (không phải 500)")
    void register_passwordOverBcryptLimit_returns400() {
        HttpResponse<String> r = send("POST", "/api/users", """
                {"email":"a@example.com","fullName":"A","username":"alice","password":"%s"}
                """.formatted("ệ".repeat(25)), null);

        assertProblem(r, 400, "validation");
        assertThat(json(r).get("errors")).asInstanceOf(InstanceOfAssertFactories.MAP).containsOnlyKeys("password");
        assertThat(count("users")).isZero();
    }

    @Test
    @DisplayName("Email trùng lọt qua bước kiểm tra trước (2 request cùng lúc) → DB chặn, vẫn trả 409 duplicate-email")
    void duplicateEmailCaughtByDatabase_returnsDuplicateEmail() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement("""
                    INSERT INTO users (email, full_name) VALUES ('race@example.com', 'Bản đến trước')
                    """)) {
                ps.executeUpdate();     // chưa commit: request bên dưới không thấy dòng này ở bước existsByEmail
            }

            CompletableFuture<HttpResponse<String>> pending =
                    CompletableFuture.supplyAsync(() -> register("race@example.com", "race"));
            awaitSessionWaitingForLock();   // request đã tới INSERT và đang chờ transaction kia
            other.commit();

            assertProblem(pending.get(30, TimeUnit.SECONDS), 409, "duplicate-email");
        }
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("accounts")).isZero();
    }

    /*
     * Hai test dưới: nhiều request đăng ký trùng gửi cùng lúc. Khoảng 10 request (bằng số connection của pool)
     * cùng qua bước existsBy... trước khi request đầu tiên commit; chỉ unique constraint của DB chặn được chúng.
     * Bỏ constraint thì bản thử nghiệm tạo ra 10 người dùng cùng một email.
     */

    @Test
    @DisplayName("20 request đăng ký cùng email, cùng lúc → đúng 1 lượt 201, 19 lượt 409 duplicate-email, chỉ 1 người dùng")
    void sameEmailAtOnce_registersExactlyOne() throws Exception {
        List<HttpResponse<String>> responses = Concurrently.run(20, i -> register("same@example.com", "user" + i));

        assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.statusCode() != 201)
                .hasSize(19)
                .allSatisfy(r -> assertProblem(r, 409, "duplicate-email"));
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("accounts")).isEqualTo(1);
    }

    @Test
    @DisplayName("20 request đăng ký cùng username, cùng lúc → đúng 1 lượt 201, 19 lượt 409 duplicate-username, "
            + "hồ sơ của lượt thua cũng không được tạo")
    void sameUsernameAtOnce_registersExactlyOne() throws Exception {
        List<HttpResponse<String>> responses = Concurrently.run(20, i -> register("user" + i + "@example.com", "same"));

        assertThat(responses).filteredOn(r -> r.statusCode() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.statusCode() != 201)
                .hasSize(19)
                .allSatisfy(r -> assertProblem(r, 409, "duplicate-username"));
        // users được INSERT trước accounts: lượt thua đã ghi dòng users rồi mới gặp lỗi ở accounts, dòng đó phải rollback
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("accounts")).isEqualTo(1);
    }

    // =====================================================================
    // 2. Hồ sơ
    // =====================================================================

    @Test
    @DisplayName("PATCH → chỉ đổi trường gửi lên; version tăng, updatedAt đổi; phone rỗng = xoá")
    void updateProfile_changesOnlySentFields() {
        Map<String, Object> created = json(send("POST", "/api/users", """
                {"email":"a@example.com","fullName":"Nguyễn Văn A","phone":"0912345678",
                 "username":"alice","password":"matkhau123"}
                """, null));
        long id = idOf(created);
        Instant createdAt = Instant.parse(created.get("createdAt").toString());

        Map<String, Object> renamed = json(send("PATCH", "/api/users/" + id, """
                {"fullName":"  Nguyễn Văn An ","version":0}
                """, null));
        assertThat(renamed.get("fullName")).isEqualTo("Nguyễn Văn An");
        assertThat(renamed.get("phone")).isEqualTo("0912345678");
        assertThat(renamed.get("version")).isEqualTo(1);
        assertThat(Instant.parse(renamed.get("updatedAt").toString())).isAfter(createdAt);

        Map<String, Object> phoneCleared = json(send("PATCH", "/api/users/" + id, """
                {"phone":"","version":1}
                """, null));
        assertThat(phoneCleared.get("phone")).isNull();
        assertThat(phoneCleared.get("fullName")).isEqualTo("Nguyễn Văn An");
    }

    @Test
    @DisplayName("PATCH tên chỉ có khoảng trắng, phone sai hoặc thiếu version → 400; id không tồn tại → 404")
    void updateProfile_invalidOrUnknown_isRejected() {
        long id = idOf(json(register("a@example.com", "alice")));

        HttpResponse<String> blank = send("PATCH", "/api/users/" + id, """
                {"fullName":" \\t ","phone":"abc"}
                """, null);
        assertProblem(blank, 400, "validation");
        assertThat(json(blank).get("errors")).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("fullName", "phone", "version");

        assertProblem(send("PATCH", "/api/users/999999999", """
                {"fullName":"X","version":0}
                """, null), 404, "user-not-found");
        assertProblem(send("GET", "/api/users/999999999", null, null), 404, "user-not-found");
    }

    @Test
    @DisplayName("Sửa dựa trên version cũ (người khác đã sửa sau lần mình đọc) → 409 concurrent-modification, không ghi đè")
    void updateProfile_staleVersion_returns409() {
        long id = idOf(json(register("a@example.com", "alice")));
        // An và Bình cùng đọc hồ sơ ở version 0; An sửa trước
        assertThat(send("PATCH", "/api/users/" + id, """
                {"phone":"0911111111","version":0}
                """, null).statusCode()).isEqualTo(200);

        HttpResponse<String> stale = send("PATCH", "/api/users/" + id, """
                {"phone":"0922222222","version":0}
                """, null);

        assertProblem(stale, 409, "concurrent-modification");
        assertThat(json(stale)).containsEntry("expectedVersion", 0).containsEntry("currentVersion", 1);
        Map<String, Object> user = json(send("GET", "/api/users/" + id, null, null));
        assertThat(user.get("phone")).isEqualTo("0911111111");   // thay đổi của An còn nguyên
        assertThat(user.get("version")).isEqualTo(1);
    }

    @Test
    @DisplayName("20 PATCH cùng lúc, cùng đọc version 0 → đúng 1 lượt thành công, 19 lượt 409, không lượt nào ghi đè lượt khác")
    void concurrentUpdatesWithSameVersion_onlyOneWins() throws Exception {
        long id = idOf(json(register("a@example.com", "alice")));

        List<HttpResponse<String>> responses = Concurrently.run(20, i -> send("PATCH", "/api/users/" + id, """
                {"fullName":"Tên %d","version":0}
                """.formatted(i), null));

        List<HttpResponse<String>> won = responses.stream().filter(r -> r.statusCode() == 200).toList();
        assertThat(won).hasSize(1);
        assertThat(responses).filteredOn(r -> r.statusCode() != 200)
                .hasSize(19)
                .allSatisfy(r -> assertProblem(r, 409, "concurrent-modification"));
        Map<String, Object> user = json(send("GET", "/api/users/" + id, null, null));
        assertThat(user.get("fullName")).isEqualTo(json(won.getFirst()).get("fullName"));
        assertThat(user.get("version")).isEqualTo(1);
    }

    // =====================================================================
    // 3. Khoá / mở khoá tài khoản
    // =====================================================================

    @Test
    @DisplayName("Khoá → LOCKED, khoá lại vẫn 200; mở khoá → ACTIVE")
    void lockAndUnlock() {
        long id = idOf(json(register("a@example.com", "alice")));

        assertThat(accountStatus(send("POST", "/api/users/" + id + "/lock", null, null))).isEqualTo("LOCKED");
        assertThat(accountStatus(send("POST", "/api/users/" + id + "/lock", null, null))).isEqualTo("LOCKED");
        assertThat(accountStatus(send("GET", "/api/users/" + id, null, null))).isEqualTo("LOCKED");

        assertThat(accountStatus(send("POST", "/api/users/" + id + "/unlock", null, null))).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT status FROM accounts").query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Tài khoản đã vô hiệu hoá → khoá / mở khoá đều 409 account-disabled, trạng thái giữ nguyên")
    void disabledAccount_cannotBeLockedOrUnlocked() {
        long id = idOf(json(register("a@example.com", "alice")));
        jdbc.sql("UPDATE accounts SET status = 'DISABLED'").update();

        assertProblem(send("POST", "/api/users/" + id + "/lock", null, null), 409, "account-disabled");
        assertProblem(send("POST", "/api/users/" + id + "/unlock", null, null), 409, "account-disabled");
        assertThat(jdbc.sql("SELECT status FROM accounts").query(String.class).single()).isEqualTo("DISABLED");
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private HttpResponse<String> register(String email, String username) {
        return send("POST", "/api/users", """
                {"email":"%s","fullName":"Người dùng %s","username":"%s","password":"matkhau123"}
                """.formatted(email, username, username), null);
    }

    @SuppressWarnings("unchecked")
    private String accountStatus(HttpResponse<String> r) {
        assertThat(r.statusCode()).isEqualTo(200);
        return (String) ((Map<String, Object>) json(r).get("account")).get("status");
    }
}
