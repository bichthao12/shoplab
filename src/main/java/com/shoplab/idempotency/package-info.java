/**
 * Module idempotency: chạy request ghi đúng một lần theo header Idempotency-Key. Không biết gì về nghiệp vụ.
 *
 * API cho module khác (package này): IdempotencyService và các exception của nó.
 * Nội bộ: internal/ (lưu key trong bảng idempotency_keys, băm request, job dọn key cũ).
 */
@ApplicationModule(displayName = "Idempotency", allowedDependencies = "common")
package com.shoplab.idempotency;

import org.springframework.modulith.ApplicationModule;
