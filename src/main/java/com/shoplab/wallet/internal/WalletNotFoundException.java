package com.shoplab.wallet.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

class WalletNotFoundException extends ApiException {
    WalletNotFoundException(long walletId) {
        super(HttpStatus.NOT_FOUND, "wallet-not-found", "Wallet Not Found", "Không tìm thấy ví có id = " + walletId);
    }
}
