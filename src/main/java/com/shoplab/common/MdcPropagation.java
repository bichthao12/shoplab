package com.shoplab.common;

import org.slf4j.MDC;

import java.util.Map;

/**
 * Chép MDC (traceId...) của luồng đang gọi sang luồng chạy việc. MDC là ThreadLocal: việc giao cho executor chạy trên
 * luồng khác, luồng đó không có MDC của request, nên log của nó mất traceId.
 *
 * Chụp MDC lúc GIAO việc (trên luồng gọi), đặt vào luồng chạy việc trước khi chạy, và trả MDC cũ của luồng đó lại sau
 * khi xong: luồng của pool được dùng lại cho việc khác thì không mang traceId của request trước sang.
 */
public final class MdcPropagation {

    private MdcPropagation() {
    }

    public static Runnable withCurrentMdc(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            set(context);
            try {
                task.run();
            } finally {
                set(previous);
            }
        };
    }

    private static void set(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
