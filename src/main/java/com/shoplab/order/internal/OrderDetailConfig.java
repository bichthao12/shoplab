package com.shoplab.order.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(OrderDetailProperties.class)
class OrderDetailConfig {

    /**
     * Luồng chạy các nguồn của trang chi tiết đơn: mỗi việc một luồng ảo (Java 21), tên order-detail-N trong log.
     * Các nguồn chủ yếu NGỒI CHỜ (DB, dịch vụ ngoài): luồng ảo đang chờ không giữ luồng hệ điều hành nào, nên không cần
     * giới hạn số luồng. Không dùng ForkJoinPool chung (mặc định của CompletableFuture.supplyAsync): pool đó chỉ có
     * số luồng bằng số CPU, vài request đang chờ là chiếm hết, mọi thứ khác dùng chung pool phải đợi.
     * Số kết nối DB thì vẫn giới hạn bởi pool Hikari.
     * Khi app tắt, Spring gọi close(): chờ các việc đang chạy xong.
     */
    @Bean
    ExecutorService orderDetailExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("order-detail-", 0).factory());
    }
}
