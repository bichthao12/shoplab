package com.shoplab;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bảng "mức cô lập × hiện tượng" của PostgreSQL, lập bằng thí nghiệm thay vì chép từ tài liệu (docs/concurrency-notes.md).
 *
 * Mỗi thí nghiệm dùng hai connection T1, T2 chạy TỪNG BƯỚC theo thứ tự cố định trên cùng một luồng. Không bước nào
 * phải chờ khoá (transaction kia đã commit trước khi cần), nên kết quả không phụ thuộc thời điểm.
 * Bảng thí nghiệm nằm trong schema riêng isolation_lab, xoá sau mỗi test (không đụng các bảng của module).
 *
 * Kết quả mỗi ô:
 *  - ANOMALY: hiện tượng xảy ra (dữ liệu sai mà không ai báo lỗi);
 *  - ABORTED: PostgreSQL chặn bằng lỗi 40001 (serialization failure), ứng dụng phải chạy lại transaction;
 *  - PREVENTED: không xảy ra và cũng không có lỗi (snapshot của transaction không thấy thay đổi của bên kia).
 */
class IsolationLevelTests extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(IsolationLevelTests.class);
    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    enum Level {
        READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
        REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
        SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

        final int jdbc;

        Level(int jdbc) { this.jdbc = jdbc; }
    }

    enum Phenomenon { LOST_UPDATE, WRITE_SKEW, PHANTOM }

    enum Outcome { ANOMALY, ABORTED, PREVENTED }

    @BeforeEach
    void createLab() {
        jdbc.sql("DROP SCHEMA IF EXISTS isolation_lab CASCADE").update();
        jdbc.sql("CREATE SCHEMA isolation_lab").update();
        jdbc.sql("CREATE TABLE isolation_lab.account (id int PRIMARY KEY, balance int NOT NULL)").update();
        jdbc.sql("INSERT INTO isolation_lab.account VALUES (1, 100), (2, 100)").update();
        jdbc.sql("CREATE TABLE isolation_lab.doctor (name text PRIMARY KEY, on_call boolean NOT NULL)").update();
        jdbc.sql("INSERT INTO isolation_lab.doctor VALUES ('alice', true), ('bob', true)").update();
        jdbc.sql("CREATE TABLE isolation_lab.item (id serial PRIMARY KEY, category text NOT NULL)").update();
        jdbc.sql("INSERT INTO isolation_lab.item (category) VALUES ('x'), ('x')").update();
    }

    @AfterEach
    void dropLab() {
        jdbc.sql("DROP SCHEMA IF EXISTS isolation_lab CASCADE").update();
    }

    static Stream<Arguments> table() {
        return Stream.of(
                Arguments.of(Phenomenon.LOST_UPDATE, Level.READ_COMMITTED, Outcome.ANOMALY),
                Arguments.of(Phenomenon.LOST_UPDATE, Level.REPEATABLE_READ, Outcome.ABORTED),
                Arguments.of(Phenomenon.LOST_UPDATE, Level.SERIALIZABLE, Outcome.ABORTED),
                Arguments.of(Phenomenon.WRITE_SKEW, Level.READ_COMMITTED, Outcome.ANOMALY),
                Arguments.of(Phenomenon.WRITE_SKEW, Level.REPEATABLE_READ, Outcome.ANOMALY),
                Arguments.of(Phenomenon.WRITE_SKEW, Level.SERIALIZABLE, Outcome.ABORTED),
                Arguments.of(Phenomenon.PHANTOM, Level.READ_COMMITTED, Outcome.ANOMALY),
                Arguments.of(Phenomenon.PHANTOM, Level.REPEATABLE_READ, Outcome.PREVENTED),
                Arguments.of(Phenomenon.PHANTOM, Level.SERIALIZABLE, Outcome.PREVENTED));
    }

    @ParameterizedTest(name = "{0} ở {1} → {2}")
    @MethodSource("table")
    @DisplayName("Mức cô lập × hiện tượng")
    void isolationLevel_vsPhenomenon(Phenomenon phenomenon, Level level, Outcome expected) throws SQLException {
        Outcome outcome = switch (phenomenon) {
            case LOST_UPDATE -> lostUpdate(level);
            case WRITE_SKEW -> writeSkew(level);
            case PHANTOM -> phantom(level);
        };
        log.info("[isolation] {} @ {} -> {}", phenomenon, level, outcome);
        assertThat(outcome).isEqualTo(expected);
    }

    /**
     * Lost update: hai bên cùng đọc số dư 100, mỗi bên cộng thêm ở Java rồi ghi con số đã tính.
     * Đúng thì cuối cùng 130 (hoặc một bên bị huỷ); lost update thì 120: phần +10 của T1 mất.
     */
    private Outcome lostUpdate(Level level) throws SQLException {
        try (Connection t1 = begin(level); Connection t2 = begin(level)) {
            try {
                int seenByT1 = queryInt(t1, "SELECT balance FROM isolation_lab.account WHERE id = 1");   // 100
                int seenByT2 = queryInt(t2, "SELECT balance FROM isolation_lab.account WHERE id = 1");   // 100
                update(t1, "UPDATE isolation_lab.account SET balance = " + (seenByT1 + 10) + " WHERE id = 1");
                t1.commit();
                update(t2, "UPDATE isolation_lab.account SET balance = " + (seenByT2 + 20) + " WHERE id = 1");
                t2.commit();
            } catch (SQLException e) {
                return abortedOrRethrow(e, t1, t2);
            }
        }
        int balance = jdbc.sql("SELECT balance FROM isolation_lab.account WHERE id = 1").query(Integer.class).single();
        return balance == 120 ? Outcome.ANOMALY : Outcome.PREVENTED;
    }

    /**
     * Write skew: quy tắc "luôn còn ít nhất 1 bác sĩ trực". Mỗi bên thấy 2 người đang trực nên tự xin nghỉ,
     * mỗi bên sửa một dòng KHÁC nhau. Riêng từng transaction thì hợp lệ, cộng lại thì không còn ai trực.
     */
    private Outcome writeSkew(Level level) throws SQLException {
        try (Connection t1 = begin(level); Connection t2 = begin(level)) {
            try {
                int onCallSeenByT1 = queryInt(t1, "SELECT count(*) FROM isolation_lab.doctor WHERE on_call");   // 2
                int onCallSeenByT2 = queryInt(t2, "SELECT count(*) FROM isolation_lab.doctor WHERE on_call");   // 2
                if (onCallSeenByT1 >= 2) update(t1, "UPDATE isolation_lab.doctor SET on_call = false WHERE name = 'alice'");
                if (onCallSeenByT2 >= 2) update(t2, "UPDATE isolation_lab.doctor SET on_call = false WHERE name = 'bob'");
                t1.commit();
                t2.commit();
            } catch (SQLException e) {
                return abortedOrRethrow(e, t1, t2);
            }
        }
        int onCall = jdbc.sql("SELECT count(*) FROM isolation_lab.doctor WHERE on_call").query(Integer.class).single();
        return onCall == 0 ? Outcome.ANOMALY : Outcome.PREVENTED;
    }

    /**
     * Phantom: T1 đếm các dòng thoả điều kiện hai lần; giữa hai lần, T2 thêm một dòng thoả điều kiện và commit.
     * Phantom thì lần đếm thứ hai ra số khác lần đầu trong cùng một transaction.
     */
    private Outcome phantom(Level level) throws SQLException {
        try (Connection t1 = begin(level); Connection t2 = dataSource.getConnection()) {
            try {
                int first = queryInt(t1, "SELECT count(*) FROM isolation_lab.item WHERE category = 'x'");    // 2
                update(t2, "INSERT INTO isolation_lab.item (category) VALUES ('x')");                       // autocommit
                int second = queryInt(t1, "SELECT count(*) FROM isolation_lab.item WHERE category = 'x'");
                t1.commit();
                return second != first ? Outcome.ANOMALY : Outcome.PREVENTED;
            } catch (SQLException e) {
                return abortedOrRethrow(e, t1);
            }
        }
    }

    // =====================================================================
    // Câu hỏi: SELECT ... FOR UPDATE có khoá cả bảng không?
    // =====================================================================

    @Test
    @DisplayName("SELECT ... FOR UPDATE chỉ khoá dòng trả về: dòng khác sửa được, thêm dòng được, đọc được; chỉ DDL / LOCK TABLE bị chặn")
    void selectForUpdate_locksReturnedRowsNotTable() throws SQLException {
        try (Connection t1 = begin(Level.READ_COMMITTED); Connection t2 = dataSource.getConnection()) {
            queryInt(t1, "SELECT balance FROM isolation_lab.account WHERE id = 1 FOR UPDATE");
            int t1Pid = queryInt(t1, "SELECT pg_backend_pid()");

            List<String> tableLocks = jdbc.sql("""
                            SELECT l.mode FROM pg_locks l JOIN pg_class c ON c.oid = l.relation
                            WHERE c.relname = 'account' AND c.relnamespace = 'isolation_lab'::regnamespace AND l.pid = :pid
                            """)
                    .param("pid", t1Pid).query(String.class).list();
            List<String> results = new ArrayList<>();
            results.add("khoá mức bảng của T1: " + tableLocks);
            results.add("đọc dòng 1 (SELECT thường): " + tryWithShortLockTimeout(t2, "SELECT balance FROM isolation_lab.account WHERE id = 1"));
            results.add("sửa dòng 2: " + tryWithShortLockTimeout(t2, "UPDATE isolation_lab.account SET balance = balance + 1 WHERE id = 2"));
            results.add("thêm dòng 3: " + tryWithShortLockTimeout(t2, "INSERT INTO isolation_lab.account VALUES (3, 0)"));
            results.add("sửa dòng 1: " + tryWithShortLockTimeout(t2, "UPDATE isolation_lab.account SET balance = balance + 1 WHERE id = 1"));
            results.add("LOCK TABLE ... EXCLUSIVE: " + tryWithShortLockTimeout(t2, "LOCK TABLE isolation_lab.account IN EXCLUSIVE MODE NOWAIT"));
            t1.rollback();
            results.forEach(r -> log.info("[for-update] {}", r));

            assertThat(tableLocks).containsExactly("RowShareLock");
            assertThat(results).containsExactly(
                    "khoá mức bảng của T1: [RowShareLock]",
                    "đọc dòng 1 (SELECT thường): chạy được",
                    "sửa dòng 2: chạy được",
                    "thêm dòng 3: chạy được",
                    "sửa dòng 1: bị chặn (" + LOCK_NOT_AVAILABLE + ")",
                    "LOCK TABLE ... EXCLUSIVE: bị chặn (" + LOCK_NOT_AVAILABLE + ")");
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Connection đã mở transaction ở mức cô lập level (Hikari trả mức cô lập, autocommit về mặc định khi đóng). */
    private Connection begin(Level level) throws SQLException {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        c.setTransactionIsolation(level.jdbc);
        return c;
    }

    private static int queryInt(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void update(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /** Lỗi 40001 → ABORTED (rollback cả hai bên); lỗi khác thì ném tiếp. */
    private static Outcome abortedOrRethrow(SQLException e, Connection... connections) throws SQLException {
        for (Connection c : connections) {
            if (!c.getAutoCommit()) c.rollback();
        }
        if (SERIALIZATION_FAILURE.equals(e.getSQLState())) {
            log.info("[isolation] 40001: {}", e.getMessage().lines().findFirst().orElse(""));
            return Outcome.ABORTED;
        }
        throw e;
    }

    /**
     * Chạy câu lệnh trong một transaction riêng với lock_timeout 300ms (SET LOCAL: không đổi cấu hình của connection
     * trong pool), rồi rollback. Trả "chạy được" hoặc "bị chặn (mã lỗi)".
     */
    private static String tryWithShortLockTimeout(Connection c, String sql) throws SQLException {
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("SET LOCAL lock_timeout = '300ms'");
            st.execute(sql);
            return "chạy được";
        } catch (SQLException e) {
            return "bị chặn (" + e.getSQLState() + ")";
        } finally {
            c.rollback();
            c.setAutoCommit(true);
        }
    }
}
