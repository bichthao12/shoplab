package com.shoplab.product.internal;

import com.shoplab.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test slice cho repository: chỉ dựng JPA + DB (không web, không service).
 * Tự quản lý transaction (NOT_SUPPORTED) để kiểm tra được khoá giữ tới hết transaction rồi mới nhả.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductRepositoryTests {

    @Autowired ProductRepository repo;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach
    void cleanUp() {
        JdbcClient.create(dataSource).sql("DELETE FROM products WHERE sku LIKE 'REPO-%'").update();
    }

    @Test
    @DisplayName("findAllByIdInForUpdate: trả theo thứ tự id và khoá các dòng tới hết transaction")
    void findAllByIdInForUpdate_locksRowsInIdOrderUntilCommit() {
        long first = insertProduct("REPO-A");
        long second = insertProduct("REPO-B");

        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            List<Product> locked = repo.findAllByIdInForUpdate(List.of(second, first));

            assertThat(locked).extracting(Product::getId).containsExactly(first, second);
            assertThat(canLockFromAnotherConnection(first)).isFalse();
            assertThat(canLockFromAnotherConnection(second)).isFalse();
        });

        assertThat(canLockFromAnotherConnection(first)).isTrue();   // commit xong → khoá đã được nhả
    }

    private long insertProduct(String sku) {
        return JdbcClient.create(dataSource).sql("""
                        INSERT INTO products (sku, name, category, price, stock)
                        VALUES (:sku, 'Sản phẩm test', 'test', 1, 1)
                        RETURNING id
                        """)
                .param("sku", sku)
                .query(Long.class)
                .single();
    }

    /** Thử khoá dòng từ một connection khác, không chờ (NOWAIT): false nếu dòng đang bị transaction khác khoá. */
    private boolean canLockFromAnotherConnection(long productId) {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement(
                    "SELECT id FROM products WHERE id = ? FOR UPDATE NOWAIT")) {
                ps.setLong(1, productId);
                ps.executeQuery().close();
                return true;
            } catch (SQLException e) {
                if ("55P03".equals(e.getSQLState())) {   // lock_not_available
                    return false;
                }
                throw e;
            } finally {
                other.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
