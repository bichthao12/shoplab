package com.shoplab.product.web;

import com.shoplab.common.ValidationPatterns;
import com.shoplab.product.internal.UpdateProductCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * PATCH: mọi trường đều tuỳ chọn, trừ version. Trường nào null (không gửi) thì giữ nguyên.
 * Các constraint của Bean Validation (trừ @NotNull) bỏ qua giá trị null,
 * nên chỉ kiểm tra những trường client thực sự gửi lên.
 *
 * version: BẮT BUỘC, là version của sản phẩm mà client đã đọc (GET hoặc response lần sửa trước).
 * Sản phẩm đã bị sửa sau lần đọc đó (version khác) → 409 concurrent-modification, không ghi đè.
 * Mỗi lần giữ hàng cho đơn cũng tăng version: client không thể ghi đè tồn kho bằng con số đọc từ trước đó.
 */
public record PatchProductRequest(
        @Size(max = 64) @Pattern(regexp = ValidationPatterns.NOT_BLANK, message = "không được để trống") String sku,
        @Size(max = 255) @Pattern(regexp = ValidationPatterns.NOT_BLANK, message = "không được để trống") String name,
        @Size(max = 5000) String description,
        @Size(max = 50) @Pattern(regexp = ValidationPatterns.NOT_BLANK, message = "không được để trống") String category,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @PositiveOrZero Integer stock,
        Boolean active,
        @NotNull @PositiveOrZero Long version
) {
    /** Chuyển sang đầu vào của ProductService (gọi sau khi đã qua validation). */
    public UpdateProductCommand toCommand() {
        return new UpdateProductCommand(sku, name, description, category, price, stock, active, version);
    }
}
