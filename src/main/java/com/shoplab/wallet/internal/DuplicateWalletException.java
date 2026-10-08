package com.shoplab.wallet.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

class DuplicateWalletException extends ApiException {
    DuplicateWalletException(long userId) {
        super(HttpStatus.CONFLICT, "duplicate-wallet", "Duplicate Wallet", "Người dùng id = " + userId + " đã có ví");
    }
}
