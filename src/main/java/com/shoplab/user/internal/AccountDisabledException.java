package com.shoplab.user.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

class AccountDisabledException extends ApiException {
    AccountDisabledException(String username) {
        super(HttpStatus.CONFLICT, "account-disabled", "Account Disabled",
                "Tài khoản '" + username + "' đã bị vô hiệu hoá, không thể khoá hay mở khoá");
    }
}
