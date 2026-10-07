package com.shoplab.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Bật Spring Data JPA auditing cho AuditedEntity.
 * Đặt ở class riêng (không đặt trên ShoplabApplication) để các test slice như @WebMvcTest không phải dựng JPA.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    /**
     * Thời điểm dạng Instant (UTC), không qua LocalDateTime và múi giờ của máy chủ.
     * Cắt về micro giây – đúng độ chính xác của TIMESTAMPTZ – để giá trị trả về ngay khi tạo / sửa
     * khớp với giá trị đọc lại từ DB sau đó.
     */
    @Bean
    DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
}
