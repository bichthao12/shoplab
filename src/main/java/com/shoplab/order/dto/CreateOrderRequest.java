package com.shoplab.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Body của POST /api/orders. Chỉ mang dữ liệu + validation; chuẩn hoá nằm ở CreateOrderCommand. */
public record CreateOrderRequest(
        @NotBlank @Size(max = 255) String customerName,
        @NotBlank @Email @Size(max = 255) String customerEmail,
        @NotEmpty @Size(max = 50) List<@NotNull @Valid Item> items
) {

    public record Item(
            @NotNull @Positive Long productId,
            @NotNull @Min(1) @Max(1000) Integer quantity
    ) {}
}
