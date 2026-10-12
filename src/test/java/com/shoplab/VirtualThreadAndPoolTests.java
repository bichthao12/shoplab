package com.shoplab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cấu hình cho bài tải k6 (scripts/load-test.ps1): request chạy trên luồng ảo (spring.threads.virtual.enabled), và
 * Actuator mở được số đo của pool kết nối (hikaricp.connections.*) mà kịch bản k6 đọc trong lúc chạy.
 */
@ExtendWith(OutputCaptureExtension.class)
class VirtualThreadAndPoolTests extends IntegrationTestBase {

    @Test
    @DisplayName("Request chạy trên luồng ảo tomcat-handler-N, không phải luồng thường nio-...-exec-N")
    void requestsRunOnVirtualThreads(CapturedOutput output) {
        long orderId = idOf(json(postOrder(newKey(), orderJson(createProduct("VT-1", 100_000, 10), 1))));
        int from = output.getOut().length();   // chỉ xét log của request bên dưới

        assertThat(send("GET", "/api/orders/" + orderId, null, null).statusCode()).isEqualTo(200);

        // Log in tên luồng cắt còn 15 ký tự cuối, vd "[omcat-handler-7]"
        String begin = output.getOut().substring(from).lines()
                .filter(line -> line.contains("BEGIN    OrderService.getById"))
                .findFirst().orElseThrow();
        assertThat(begin).containsPattern("\\[\\s*\\S*handler-\\d+\\]").doesNotContain("-exec-");
    }

    @Test
    @DisplayName("Actuator: health UP, số đo pool kết nối (pending, max) đọc được qua /actuator/metrics")
    void actuatorExposesPoolMetrics() {
        HttpResponse<String> health = send("GET", "/actuator/health", null, null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(json(health)).containsEntry("status", "UP");

        assertThat(metricValue("hikaricp.connections.pending")).isGreaterThanOrEqualTo(0);
        assertThat(metricValue("hikaricp.connections.max")).isEqualTo(10);
    }

    @SuppressWarnings("unchecked")
    private double metricValue(String name) {
        HttpResponse<String> r = send("GET", "/actuator/metrics/" + name, null, null);
        assertThat(r.statusCode()).as(name).isEqualTo(200);
        List<Map<String, Object>> measurements = (List<Map<String, Object>>) json(r).get("measurements");
        return measurements.stream()
                .filter(m -> "VALUE".equals(m.get("statistic")))
                .map(m -> ((Number) m.get("value")).doubleValue())
                .findFirst().orElseThrow();
    }
}
