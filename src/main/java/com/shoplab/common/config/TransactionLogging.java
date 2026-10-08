package com.shoplab.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Ghi log khi mỗi transaction MỚI bắt đầu, commit hoặc rollback, kèm tên transaction (Class.method).
 * Đọc cùng log SQL (logging.level.sql) sẽ thấy câu nào nằm trong transaction nào và commit lúc nào.
 *
 *  - COMMIT / ROLLBACK được ghi SAU khi connection đã commit / rollback xong, tức là sau mọi câu SQL
 *    Hibernate flush lúc commit.
 *  - Transaction chỉ tham gia vào transaction có sẵn (vd repository gọi trong service) không có dòng riêng.
 *
 * Spring Boot tự gắn mọi bean TransactionExecutionListener vào transaction manager.
 * Bật / tắt bằng logging.level.tx (xem application.properties).
 */
@Component
class TransactionLogging implements TransactionExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionLogging.class);

    @Override
    public void afterBegin(TransactionExecution tx, Throwable beginFailure) {
        if (beginFailure != null) {
            log.debug("BEGIN FAILED {}: {}", name(tx), beginFailure.toString());
        } else {
            log.debug("BEGIN    {}{}", name(tx), tx.isReadOnly() ? " (read-only)" : "");
        }
    }

    @Override
    public void afterCommit(TransactionExecution tx, Throwable commitFailure) {
        if (commitFailure != null) {
            log.debug("COMMIT FAILED {}: {}", name(tx), commitFailure.toString());
        } else {
            log.debug("COMMIT   {}", name(tx));
        }
    }

    @Override
    public void afterRollback(TransactionExecution tx, Throwable rollbackFailure) {
        if (rollbackFailure != null) {
            log.debug("ROLLBACK FAILED {}: {}", name(tx), rollbackFailure.toString());
        } else {
            log.debug("ROLLBACK {}", name(tx));
        }
    }

    /** "com.shoplab.order.internal.OrderService.create" → "OrderService.create". */
    private static String name(TransactionExecution tx) {
        String name = tx.getTransactionName();
        if (name.isEmpty()) {
            return "(không tên)";
        }
        int method = name.lastIndexOf('.');
        int type = method > 0 ? name.lastIndexOf('.', method - 1) : -1;
        return name.substring(type + 1);
    }
}
