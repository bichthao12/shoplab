package com.shoplab.order;

/** Body đúng cú pháp nhưng không xử lý được (sản phẩm không tồn tại, ngừng bán...) → 422. */
public class InvalidOrderException extends RuntimeException {
    public InvalidOrderException(String message) {
        super(message);
    }
}
