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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record CreateOrderRequest(
        @NotBlank @Size(max = 255) String customerName,
        @NotBlank @Email @Size(max = 255) String customerEmail,
        @NotEmpty @Size(max = 50) List<@NotNull @Valid Item> items
) {

    public record Item(
            @NotNull @Positive Long productId,
            @NotNull @Min(1) @Max(1000) Integer quantity
    ) {}

    /** Gộp các dòng trùng productId (cộng quantity), sắp theo productId. */
    public Map<Long, Integer> mergedItems() {
        Map<Long, Integer> merged = new TreeMap<>();
        for (Item item : items) {
            merged.merge(item.productId(), item.quantity(), Integer::sum);
        }
        return merged;
    }

    /**
     * "Dấu vân tay" của request: SHA-256 của nội dung đã chuẩn hoá.
     * Cùng nội dung (kể cả khác thứ tự items, khác hoa/thường email) → cùng hash.
     */
    public String fingerprint() {
        String canonical = customerName.trim()
                + "|" + customerEmail.trim().toLowerCase(Locale.ROOT)
                + "|" + mergedItems().entrySet().stream()
                        .map(e -> e.getKey() + ":" + e.getValue())
                        .collect(Collectors.joining(","));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
