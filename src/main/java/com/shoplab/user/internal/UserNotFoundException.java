package com.shoplab.user.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

class UserNotFoundException extends ApiException {
    UserNotFoundException(Long id) {
        super(HttpStatus.NOT_FOUND, "user-not-found", "User Not Found",
                "Không tìm thấy người dùng có id = " + id);
    }
}
