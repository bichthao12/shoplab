package com.shoplab.product;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

/** Không đủ hàng để bán. ProblemDetail kèm sku, requested, available. */
public class InsufficientStockException extends ApiException {

    public InsufficientStockException(String sku, int requested, int available) {
        super(HttpStatus.CONFLICT, "insufficient-stock", "Insufficient Stock",
                "Sản phẩm " + sku + " không đủ hàng: cần " + requested + ", còn " + available);
        addProperty("sku", sku);
        addProperty("requested", requested);
        addProperty("available", available);
    }
}
