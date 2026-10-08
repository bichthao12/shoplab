package com.shoplab.user.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Mật khẩu qua được validation của request nhưng không băm được. Trả về đúng dạng lỗi validation
 * (type validation, errors.password) để client xử lý như mọi lỗi dữ liệu đầu vào khác.
 */
class InvalidPasswordException extends ApiException {
    InvalidPasswordException(String reason) {
        super(HttpStatus.BAD_REQUEST, "validation", "Validation Failed", "Dữ liệu gửi lên không hợp lệ");
        addProperty("errors", Map.of("password", reason));
    }
}
