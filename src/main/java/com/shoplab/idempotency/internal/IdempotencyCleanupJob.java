package com.shoplab.idempotency.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Mỗi giờ xoá các key cũ hơn 24h (giống Stripe). Cần @EnableScheduling. */
@Component
class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);
    private static final int TTL_HOURS = 24;

    private final IdempotencyStore store;

    IdempotencyCleanupJob(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(cron = "0 0 * * * *")
    public void purgeExpiredKeys() {
        int deleted = store.deleteOlderThanHours(TTL_HOURS);
        if (deleted > 0) {
            log.info("Đã xoá {} idempotency key quá {} giờ", deleted, TTL_HOURS);
        }
    }
}
