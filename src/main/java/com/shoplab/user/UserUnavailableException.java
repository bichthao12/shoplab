package com.shoplab.user;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Người dùng không tồn tại hoặc tài khoản không ACTIVE nên không giao dịch được.
 * API đặt hàng dịch lỗi này thành invalid-order (xem OrderService).
 */
public class UserUnavailableException extends ApiException {
    public UserUnavailableException(String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "user-unavailable", "User Unavailable", detail);
    }
}
