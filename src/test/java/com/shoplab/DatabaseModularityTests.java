package com.shoplab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ranh giới module ở tầng DB (ModularityTests kiểm tra ở tầng code).
 * Các module dùng chung một schema nhưng mỗi bảng thuộc đúng một module,
 * và không có khoá ngoại nối bảng của hai module khác nhau.
 */
class DatabaseModularityTests extends IntegrationTestBase {

    /** Bảng → module sở hữu. Thêm bảng mới thì khai báo ở đây, quên thì test đỏ. */
    private static final Map<String, String> TABLE_OWNERS = Map.of(
            "products", "product",
            "orders", "order",
            "order_items", "order",
            "idempotency_keys", "idempotency",
            "users", "user",
            "accounts", "user",
            "wallets", "wallet");

    @Test
    @DisplayName("Mọi bảng đều đã khai báo thuộc module nào")
    void everyTableBelongsToAModule() {
        List<String> tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = current_schema()
                          AND table_type = 'BASE TABLE'
                          AND table_name <> 'flyway_schema_history'
                        """)
                .query(String.class)
                .list();

        assertThat(TABLE_OWNERS.keySet()).containsExactlyInAnyOrderElementsOf(tables);
    }

    @Test
    @DisplayName("Không có khoá ngoại chéo module: mỗi module tự giữ toàn vẹn dữ liệu của mình")
    void noForeignKeysAcrossModules() {
        record ForeignKey(String name, String fromTable, String toTable) {}

        List<ForeignKey> crossModule = jdbc.sql("""
                        SELECT c.conname AS name, src.relname AS from_table, dst.relname AS to_table
                        FROM pg_constraint c
                        JOIN pg_class src ON src.oid = c.conrelid
                        JOIN pg_class dst ON dst.oid = c.confrelid
                        WHERE c.contype = 'f' AND src.relnamespace = current_schema()::regnamespace
                        """)
                .query((rs, i) -> new ForeignKey(
                        rs.getString("name"), rs.getString("from_table"), rs.getString("to_table")))
                .list()
                .stream()
                .filter(fk -> !ownerOf(fk.fromTable()).equals(ownerOf(fk.toTable())))
                .toList();

        assertThat(crossModule).isEmpty();
    }

    /** Bảng chưa khai báo coi như một module riêng (everyTableBelongsToAModule sẽ báo thiếu). */
    private static String ownerOf(String table) {
        return TABLE_OWNERS.getOrDefault(table, "<chưa khai báo: " + table + ">");
    }
}
