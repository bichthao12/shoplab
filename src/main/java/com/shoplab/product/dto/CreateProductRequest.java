package com.shoplab.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateProductRequest(
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 5000) String description,
        @NotBlank @Size(max = 50) String category,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @PositiveOrZero Integer stock,
        Boolean active          // không gửi → mặc định true
) {}
