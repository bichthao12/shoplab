package com.shoplab.order.internal;

import java.time.Instant;

/** Một sự kiện thanh toán của đơn (nguồn lịch sử thanh toán). */
public record PaymentEvent(String reference, String status, Instant at) {}
