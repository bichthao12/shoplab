package com.shoplab.product.dto;

import com.shoplab.product.UpdateProductCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * PATCH: mọi trường đều tuỳ chọn. Trường nào null (không gửi) thì giữ nguyên.
 * Các constraint của Bean Validation (trừ @NotNull) bỏ qua giá trị null,
 * nên chỉ kiểm tra những trường client thực sự gửi lên.
 */
public record PatchProductRequest(
        @Size(max = 64) @Pattern(regexp = PatchProductRequest.NOT_BLANK, message = "không được để trống") String sku,
        @Size(max = 255) @Pattern(regexp = PatchProductRequest.NOT_BLANK, message = "không được để trống") String name,
        @Size(max = 5000) String description,
        @Size(max = 50) @Pattern(regexp = PatchProductRequest.NOT_BLANK, message = "không được để trống") String category,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @PositiveOrZero Integer stock,
        Boolean active
) {
    /**
     * Có ít nhất một ký tự không phải khoảng trắng: cùng nghĩa với @NotBlank và với cách entity chuẩn hoá
     * (strip), nên giá trị qua được validation không bao giờ thành chuỗi rỗng. (?s): cho phép nhiều dòng.
     */
    static final String NOT_BLANK = "(?s).*[^\\p{javaWhitespace}].*";

    /** Chuyển sang đầu vào của ProductService (gọi sau khi đã qua validation). */
    public UpdateProductCommand toCommand() {
        return new UpdateProductCommand(sku, name, description, category, price, stock, active);
    }
}
