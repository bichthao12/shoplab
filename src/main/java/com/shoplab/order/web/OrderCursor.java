package com.shoplab.order.web;

import com.shoplab.order.internal.OrderPosition;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Cursor của danh sách đơn: chuỗi client gửi lại nguyên văn để lấy trang sau. Bên trong là vị trí của đơn cuối trang
 * trước, "createdAt,id" (vd "2026-09-27T12:17:53.101481Z,9800021"), mã hoá base64url không đệm '=' để đặt thẳng vào URL.
 * Client không cần (và không nên) hiểu nội dung: đổi cách mã hoá sau này không làm hỏng client. Không ký: sửa cursor
 * chỉ đưa tới một vị trí khác trong cùng danh sách, không lộ thêm dữ liệu gì.
 */
final class OrderCursor {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final int MAX_LENGTH = 100;

    private OrderCursor() {
    }

    static String encode(OrderPosition position) {
        String raw = position.createdAt() + "," + position.id();
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws InvalidCursorException cursor không phải chuỗi do encode tạo ra */
    static OrderPosition decode(String cursor) {
        if (cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
            throw invalid();
        }
        try {
            String raw = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
            int comma = raw.indexOf(',');
            if (comma < 0) {
                throw invalid();
            }
            Instant createdAt = Instant.parse(raw.substring(0, comma));
            long id = Long.parseLong(raw.substring(comma + 1));
            if (id <= 0) {
                throw invalid();
            }
            return new OrderPosition(createdAt, id);
        } catch (IllegalArgumentException | DateTimeParseException ex) {   // base64 sai, id không phải số
            throw invalid();
        }
    }

    private static InvalidCursorException invalid() {
        return new InvalidCursorException(
                "cursor không hợp lệ: dùng đúng nguyên văn nextCursor của trang trước, bỏ cursor để lấy trang đầu");
    }
}
