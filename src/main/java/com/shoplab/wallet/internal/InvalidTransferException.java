package com.shoplab.wallet.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

/** Yêu cầu chuyển tiền đúng cú pháp nhưng không thực hiện được (vd chuyển cho chính mình) → 422. */
class InvalidTransferException extends ApiException {
    InvalidTransferException(String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-transfer", "Invalid Transfer", detail);
    }
}
