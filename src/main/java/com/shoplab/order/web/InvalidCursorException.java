package com.shoplab.order.web;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

/** Danh sách đơn: cursor hỏng, hoặc gửi tham số của phân trang offset (page, sort) → 400. */
class InvalidCursorException extends ApiException {
    InvalidCursorException(String message) {
        super(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid Cursor", message);
    }
}
