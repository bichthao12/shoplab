package com.shoplab.common;

import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lớp gốc cho lỗi nghiệp vụ của mọi module.
 * Mỗi lỗi tự mang thông tin để trả về dạng ProblemDetail, nên GlobalExceptionHandler chỉ cần
 * MỘT handler và không phải biết tới exception của từng module (module phụ thuộc vào common, không ngược lại).
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String type;      // phần cuối của URI type, vd "product-not-found"
    private final String title;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final Map<String, String> headers = new LinkedHashMap<>();

    protected ApiException(HttpStatus status, String type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    /** Thêm trường mở rộng vào ProblemDetail, vd sku / requested / available. */
    protected void addProperty(String name, Object value) {
        properties.put(name, value);
    }

    /** Thêm header vào response, vd Retry-After. */
    protected void addHeader(String name, String value) {
        headers.put(name, value);
    }

    public HttpStatus getStatus() { return status; }
    public String getType() { return type; }
    public String getTitle() { return title; }
    public Map<String, Object> getProperties() { return Collections.unmodifiableMap(properties); }
    public Map<String, String> getHeaders() { return Collections.unmodifiableMap(headers); }
}
