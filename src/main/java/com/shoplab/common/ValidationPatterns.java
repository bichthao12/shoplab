package com.shoplab.common;

/** Biểu thức dùng chung cho @Pattern trong DTO của các module. */
public final class ValidationPatterns {

    /**
     * Có ít nhất một ký tự không phải khoảng trắng: cùng nghĩa với @NotBlank và với cách entity chuẩn hoá
     * (strip), nên giá trị qua được validation không bao giờ thành chuỗi rỗng. (?s): cho phép nhiều dòng.
     * Dùng cho trường tuỳ chọn khi PATCH (null = giữ nguyên), nơi không dùng được @NotBlank.
     */
    public static final String NOT_BLANK = "(?s).*[^\\p{javaWhitespace}].*";

    /** Số điện thoại: 8–15 chữ số, có thể bắt đầu bằng '+', vd 0912345678 hoặc +84912345678. */
    public static final String PHONE = "\\+?[0-9]{8,15}";

    private ValidationPatterns() {
    }
}
