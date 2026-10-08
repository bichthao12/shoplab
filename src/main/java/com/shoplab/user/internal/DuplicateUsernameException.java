package com.shoplab.user.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

class DuplicateUsernameException extends ApiException {
    DuplicateUsernameException(String username) {
        super(HttpStatus.CONFLICT, "duplicate-username", "Duplicate Username",
                "Username '" + username + "' đã có người dùng");
    }
}
