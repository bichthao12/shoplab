package com.shoplab.product.web;

import com.shoplab.product.internal.AdjustStockCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body của POST /api/products/{id}/stock-adjustments: cộng (delta > 0, vd nhập hàng) hoặc trừ (delta < 0,
 * vd hàng hỏng) tồn kho. Là số tăng / giảm, không phải con số tồn kho mới: không cần biết tồn kho hiện tại.
 */
public record StockAdjustmentRequest(
        @NotNull @Min(-1_000_000) @Max(1_000_000) Integer delta
) {
    /** Bean Validation đọc như thuộc tính deltaNonZero; delta null đã do @NotNull báo. */
    @AssertTrue(message = "delta phải khác 0")
    public boolean isDeltaNonZero() {
        return delta == null || delta != 0;
    }

    public AdjustStockCommand toCommand(long productId) {
        return new AdjustStockCommand(productId, delta);
    }
}
