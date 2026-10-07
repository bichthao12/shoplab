package com.shoplab.order;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

/** Body đúng cú pháp nhưng không xử lý được (sản phẩm không tồn tại, ngừng bán...) → 422. */
public class InvalidOrderException extends ApiException {
    public InvalidOrderException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-order", "Invalid Order", message);
    }
}
