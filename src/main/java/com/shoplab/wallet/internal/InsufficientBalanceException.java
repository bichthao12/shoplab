package com.shoplab.wallet.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

class InsufficientBalanceException extends ApiException {
    InsufficientBalanceException(Long walletId, BigDecimal requested, BigDecimal balance) {
        super(HttpStatus.CONFLICT, "insufficient-balance", "Insufficient Balance",
                "Ví id = " + walletId + " không đủ tiền: cần " + requested + ", còn " + balance);
        addProperty("requested", requested);
        addProperty("balance", balance);
    }
}
