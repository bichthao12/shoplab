package com.shoplab.order.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

class OrderNotFoundException extends ApiException {
    OrderNotFoundException(Long id) {
        super(HttpStatus.NOT_FOUND, "order-not-found", "Order Not Found",
                "Không tìm thấy đơn hàng có id = " + id);
    }
}
