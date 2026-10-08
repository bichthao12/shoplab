package com.shoplab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Log SQL và transaction (application.properties: logging.level.sql, logging.level.tx) in ra đúng thứ tự:
 * BEGIN, rồi từng câu SQL của cả Hibernate lẫn JdbcClient, rồi COMMIT / ROLLBACK.
 */
@ExtendWith(OutputCaptureExtension.class)
class SqlLoggingTests extends IntegrationTestBase {

    private static final String TX = "DefaultIdempotencyService.execute";

    @Test
    @DisplayName("Đặt hàng thành công: BEGIN → câu SQL của JdbcClient và Hibernate → COMMIT")
    void successfulOrder_logsStatementsBetweenBeginAndCommit(CapturedOutput output) {
        String body = orderJson(createProduct("LOG-001", 100_000, 10), 1);
        int from = output.getOut().length();   // chỉ xét log của request bên dưới

        assertThat(postOrder(newKey(), body).statusCode()).isEqualTo(201);

        assertThat(output.getOut().substring(from)).containsSubsequence(
                "BEGIN    " + TX,
                "INSERT INTO idempotency_keys",   // JdbcClient
                "insert into orders",             // Hibernate
                "insert into order_items",
                "UPDATE idempotency_keys",
                "COMMIT   " + TX);
    }

    @Test
    @DisplayName("Đặt hàng lỗi (sản phẩm ngừng bán): BEGIN → ROLLBACK, không có COMMIT")
    void failedOrder_logsRollback(CapturedOutput output) {
        String body = orderJson(createProduct("LOG-002", 100_000, 10, false), 1);
        int from = output.getOut().length();

        assertProblem(postOrder(newKey(), body), 422, "invalid-order");

        assertThat(output.getOut().substring(from))
                .containsSubsequence("BEGIN    " + TX, "ROLLBACK " + TX)
                .doesNotContain("COMMIT   " + TX);
    }
}
