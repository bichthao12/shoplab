package com.shoplab.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Mỗi request một traceId, để lọc ra mọi dòng log của đúng request đó:
 *  - lấy từ header X-Trace-Id nếu client gửi và hợp lệ (chữ, số, '-', '_', tối đa 64 ký tự: nằm trong log nên không
 *    nhận ký tự lạ), không thì tự tạo 16 ký tự hex;
 *  - đặt vào MDC (khoá traceId): log pattern in nó trên mọi dòng (logging.pattern.correlation);
 *  - trả lại ở header X-Trace-Id, để client báo lỗi kèm mã này.
 * MDC gắn với LUỒNG (ThreadLocal): việc chạy trên luồng khác (executor, CompletableFuture) không tự có traceId, phải chép
 * sang (common.MdcPropagation). Chạy trước mọi filter khác để log của chúng cũng có traceId.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = incoming != null && VALID.matcher(incoming).matches() ? incoming : newTraceId();
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String newTraceId() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }
}
