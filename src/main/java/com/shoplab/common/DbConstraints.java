package com.shoplab.common;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Đọc tên constraint bị vi phạm từ lỗi DB, để service sở hữu dữ liệu tự dịch sang lỗi nghiệp vụ có nghĩa
 * (vd uk_products_sku → DuplicateSkuException) thay vì để GlobalExceptionHandler đoán.
 */
public final class DbConstraints {

    private DbConstraints() {
    }

    /** true nếu lỗi là do vi phạm đúng constraint có tên này. */
    public static boolean isViolated(DataIntegrityViolationException ex, String constraintName) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                return constraintName.equalsIgnoreCase(cve.getConstraintName());
            }
        }
        return false;
    }
}
