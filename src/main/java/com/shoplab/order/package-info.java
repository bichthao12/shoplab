/**
 * Module đơn hàng.
 *
 * Chưa có API cho module khác. Nội bộ: internal/ (entity, repository, service) và web/ (REST API /api/orders).
 * Dùng module product qua ProductInventory, module user qua UserDirectory, module idempotency qua IdempotencyService.
 * Cài đặt ProductReferences của module product (OrderProductReferences).
 */
@ApplicationModule(displayName = "Order", allowedDependencies = {"common", "product", "user", "idempotency"})
package com.shoplab.order;

import org.springframework.modulith.ApplicationModule;
