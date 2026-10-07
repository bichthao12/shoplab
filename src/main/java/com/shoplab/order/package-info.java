/**
 * Module đơn hàng.
 *
 * Chưa có API cho module khác. Nội bộ: internal/ (entity, repository, service) và web/ (REST API /api/orders).
 * Dùng module product qua ProductInventory và module idempotency qua IdempotencyService.
 */
@ApplicationModule(displayName = "Order", allowedDependencies = {"common", "product", "idempotency"})
package com.shoplab.order;

import org.springframework.modulith.ApplicationModule;
