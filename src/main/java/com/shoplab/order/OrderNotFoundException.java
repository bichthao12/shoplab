package com.shoplab.order;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(Long id) {
        super("Không tìm thấy đơn hàng có id = " + id);
    }
}
