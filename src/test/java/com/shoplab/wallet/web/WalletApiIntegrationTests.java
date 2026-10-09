package com.shoplab.wallet.web;

import com.shoplab.Concurrently;
import com.shoplab.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test cho API ví: tạo ví, nạp tiền, chuyển tiền (Idempotency-Key bắt buộc),
 * và chuyển tiền ngược chiều cùng lúc không deadlock.
 */
class WalletApiIntegrationTests extends IntegrationTestBase {

    // =====================================================================
    // 1. Tạo ví, nạp tiền
    // =====================================================================

    @Test
    @DisplayName("POST → 201 + Location, số dư 0; người dùng đã có ví → 409; người dùng không tồn tại → 422")
    void create_wallet() {
        long userId = createUser("a@example.com", "A", "a", "ACTIVE");

        HttpResponse<String> created = send("POST", "/api/wallets", """
                {"userId":%d}
                """.formatted(userId), null);
        assertThat(created.statusCode()).isEqualTo(201);
        String path = URI.create(created.headers().firstValue("Location").orElseThrow()).getPath();
        Map<String, Object> wallet = json(send("GET", path, null, null));
        assertThat(((Number) wallet.get("userId")).longValue()).isEqualTo(userId);
        assertThat(new BigDecimal(wallet.get("balance").toString())).isEqualByComparingTo("0");

        assertProblem(send("POST", "/api/wallets", """
                {"userId":%d}
                """.formatted(userId), null), 409, "duplicate-wallet");
        assertProblem(send("POST", "/api/wallets", """
                {"userId":999999999}
                """, null), 422, "user-unavailable");
        assertProblem(send("GET", "/api/wallets/999999999", null, null), 404, "wallet-not-found");
    }

    @Test
    @DisplayName("Nạp tiền bắt buộc Idempotency-Key; gửi lại cùng key → response lần đầu, không nạp lần hai")
    void deposit_isIdempotent() {
        long walletId = createWallet("a@example.com", "a");
        String key = newKey();

        HttpResponse<String> first = deposit(walletId, "100", key);
        HttpResponse<String> retry = deposit(walletId, "100.00", key);   // 100 và 100.00 là cùng yêu cầu

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(retry.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(balanceOf(walletId)).isEqualByComparingTo("100.00");
        assertProblem(deposit(walletId, "1", null), 400);
    }

    @Test
    @DisplayName("Tạo ví lọt qua bước kiểm tra trước (ví của người này đang được tạo, chưa commit) → DB chặn, vẫn trả 409 duplicate-wallet")
    void duplicateWalletCaughtByDatabase_returnsDuplicateWallet() throws Exception {
        long userId = createUser("a@example.com", "A", "a", "ACTIVE");

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement("INSERT INTO wallets (user_id, balance) VALUES (?, 0)")) {
                ps.setLong(1, userId);
                ps.executeUpdate();     // chưa commit: request bên dưới không thấy ví này ở bước existsByUserId
            }

            CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(() ->
                    send("POST", "/api/wallets", """
                            {"userId":%d}
                            """.formatted(userId), null));
            awaitSessionWaitingForLock();   // request đã tới INSERT và đang chờ unique index uk_wallets_user
            other.commit();

            assertProblem(pending.get(30, TimeUnit.SECONDS), 409, "duplicate-wallet");
        }
        assertThat(count("wallets")).isEqualTo(1);
    }

    // =====================================================================
    // 2. Chuyển tiền
    // =====================================================================

    @Test
    @DisplayName("Chuyển tiền → 200 kèm số dư hai ví; không đủ tiền → 409, chuyển cho chính mình → 422, không ví nào đổi")
    void transfer_movesMoney_andRejectsInvalid() {
        long a = createWallet("a@example.com", "a");
        long b = createWallet("b@example.com", "b");
        assertThat(deposit(a, "100", newKey()).statusCode()).isEqualTo(200);

        HttpResponse<String> r = transfer(a, b, "30", newKey());

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(walletIn(json(r), "from")).containsEntry("id", (int) a);
        assertThat(new BigDecimal(walletIn(json(r), "from").get("balance").toString())).isEqualByComparingTo("70.00");
        assertThat(new BigDecimal(walletIn(json(r), "to").get("balance").toString())).isEqualByComparingTo("30.00");

        assertProblem(transfer(a, b, "1000", newKey()), 409, "insufficient-balance");
        assertProblem(transfer(a, a, "1", newKey()), 422, "invalid-transfer");
        assertProblem(transfer(a, 999_999_999L, "1", newKey()), 404, "wallet-not-found");
        assertThat(balanceOf(a)).isEqualByComparingTo("70.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("100 lượt A→B và 100 lượt B→A cùng lúc → tất cả 200, không deadlock, số dư đúng, tổng tiền không đổi")
    void oppositeTransfersAtOnce_noDeadlock() throws Exception {
        long a = createWallet("a@example.com", "a");
        long b = createWallet("b@example.com", "b");
        deposit(a, "1000", newKey());
        deposit(b, "1000", newKey());
        int pairs = 100;

        List<HttpResponse<String>> responses = Concurrently.run(2 * pairs, i -> i % 2 == 0
                ? transfer(a, b, "1", newKey())     // A→B 1
                : transfer(b, a, "2", newKey()));   // B→A 2
        Map<String, Long> outcomes = responses.stream().collect(Collectors.groupingBy(
                r -> r.statusCode() == 200 ? "200" : r.statusCode() + " " + json(r).get("type"),
                TreeMap::new, Collectors.counting()));

        assertThat(outcomes).containsExactly(Map.entry("200", (long) 2 * pairs));
        assertThat(balanceOf(a)).isEqualByComparingTo("1100.00");   // 1000 - 100×1 + 100×2
        assertThat(balanceOf(b)).isEqualByComparingTo("900.00");    // 1000 + 100×1 - 100×2
        assertThat(totalBalance()).isEqualByComparingTo("2000.00");  // tổng mọi ví: chuyển qua lại không tạo / mất tiền
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private long createWallet(String email, String username) {
        long userId = createUser(email, "Người dùng " + username, username, "ACTIVE");
        return idOf(json(send("POST", "/api/wallets", """
                {"userId":%d}
                """.formatted(userId), null)));
    }

    private HttpResponse<String> deposit(long walletId, String amount, String key) {
        return send("POST", "/api/wallets/" + walletId + "/deposits", """
                {"amount":%s}
                """.formatted(amount), key);
    }

    private HttpResponse<String> transfer(long from, long to, String amount, String key) {
        return send("POST", "/api/wallets/transfers", """
                {"fromWalletId":%d,"toWalletId":%d,"amount":%s}
                """.formatted(from, to, amount), key);
    }

    private BigDecimal balanceOf(long walletId) {
        return jdbc.sql("SELECT balance FROM wallets WHERE id = :id").param("id", walletId).query(BigDecimal.class).single();
    }

    /** Tổng số dư mọi ví trong DB (mỗi test bắt đầu với bảng rỗng). */
    private BigDecimal totalBalance() {
        return jdbc.sql("SELECT coalesce(sum(balance), 0) FROM wallets").query(BigDecimal.class).single();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> walletIn(Map<String, Object> transfer, String side) {
        return (Map<String, Object>) transfer.get(side);
    }
}
