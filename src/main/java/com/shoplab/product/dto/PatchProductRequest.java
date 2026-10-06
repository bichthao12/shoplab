package com.shoplab.product.dto;

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
        @Size(min = 1, max = 64) @Pattern(regexp = ".*\\S.*", message = "không được để trống") String sku,
        @Size(min = 1, max = 255) @Pattern(regexp = ".*\\S.*", message = "không được để trống") String name,
        @Size(max = 5000) String description,
        @Size(min = 1, max = 50) @Pattern(regexp = ".*\\S.*", message = "không được để trống") String category,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @PositiveOrZero Integer stock,
        Boolean active
) {}
