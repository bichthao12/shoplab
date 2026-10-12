/**
 * Module dùng chung (shared kernel), mọi module khác đều được dùng.
 *
 * API: ApiException (lỗi nghiệp vụ trả dạng ProblemDetail), BaseEntity / AuditedEntity (lớp cha của entity),
 * DbConstraints, ValidationPatterns, MdcPropagation (chép MDC / traceId sang luồng khác). Nội bộ: config/ (cấu hình) và
 * web/ (GlobalExceptionHandler, TraceIdFilter).
 * Không được phụ thuộc vào module nào.
 */
@ApplicationModule(displayName = "Common", allowedDependencies = {})
package com.shoplab.common;

import org.springframework.modulith.ApplicationModule;
