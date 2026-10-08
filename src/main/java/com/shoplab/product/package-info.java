/**
 * Module sản phẩm: danh mục sản phẩm và tồn kho.
 *
 * API cho module khác (package này): ProductInventory (giữ hàng cho đơn), ReservedItem,
 * ProductUnavailableException, InsufficientStockException; ProductReferences do module có tham chiếu
 * tới sản phẩm cài đặt, để sản phẩm còn được dùng không bị xoá.
 * Nội bộ: internal/ (entity, repository, service) và web/ (REST API /api/products).
 * Dùng module idempotency qua IdempotencyService (điều chỉnh tồn kho bắt buộc Idempotency-Key).
 */
@ApplicationModule(displayName = "Product", allowedDependencies = {"common", "idempotency"})
package com.shoplab.product;

import org.springframework.modulith.ApplicationModule;
