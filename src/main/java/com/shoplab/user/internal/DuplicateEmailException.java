package com.shoplab.user.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

class DuplicateEmailException extends ApiException {
    DuplicateEmailException(String email) {
        super(HttpStatus.CONFLICT, "duplicate-email", "Duplicate Email", "Email '" + email + "' đã được đăng ký");
    }
}
