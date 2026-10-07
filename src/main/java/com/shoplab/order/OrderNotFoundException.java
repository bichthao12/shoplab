package com.shoplab.order;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

public class OrderNotFoundException extends ApiException {
    public OrderNotFoundException(Long id) {
        super(HttpStatus.NOT_FOUND, "order-not-found", "Order Not Found",
                "Không tìm thấy đơn hàng có id = " + id);
    }
}
