package com.shoplab.order.internal;

import java.util.List;

/** Nguồn lịch sử thanh toán của đơn: ở hệ thống thật là cổng thanh toán (dịch vụ ngoài). */
interface PaymentHistorySource {

    List<PaymentEvent> findByOrderId(long orderId);
}
