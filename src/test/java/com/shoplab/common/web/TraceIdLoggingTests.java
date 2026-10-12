package com.shoplab.common.web;

import com.shoplab.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId (TraceIdFilter, header X-Trace-Id) có trên mọi dòng log của request, kể cả dòng của các luồng order-detail-N
 * mà trang chi tiết đơn giao việc cho (MDC được chép sang, common.MdcPropagation).
 */
@ExtendWith(OutputCaptureExtension.class)
class TraceIdLoggingTests extends IntegrationTestBase {

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    @DisplayName("Trang chi tiết đơn: log của mọi luồng order-detail-N (đơn, sản phẩm) đều có traceId của request")
    void orderDetail_logsOfParallelTasksCarryTraceId(CapturedOutput output) throws Exception {
        long orderId = idOf(json(postOrder(newKey(), orderJson(createProduct("TRACE-1", 100_000, 10), 1))));
        int from = output.getOut().length();

        HttpResponse<String> r = get("/api/orders/" + orderId + "/detail", "trace-test-1");

        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue(TraceIdFilter.HEADER)).hasValue("trace-test-1");
        List<String> taskLines = output.getOut().substring(from).lines().filter(l -> l.contains("order-detail-")).toList();
        assertThat(taskLines)
                .as("log của lời gọi đơn và lời gọi sản phẩm (lời gọi sản phẩm được giao từ luồng của lời gọi đơn)")
                .anySatisfy(l -> assertThat(l).contains("BEGIN    OrderService.getById"))
                .anySatisfy(l -> assertThat(l).contains("BEGIN    DefaultProductCatalog.findByIds"))
                .allSatisfy(l -> assertThat(l).contains("[trace-test-1]"));
    }

    @Test
    @DisplayName("Không gửi X-Trace-Id → app tự tạo 16 ký tự hex, trả ở header, log của request có nó")
    void noHeader_generatesTraceId(CapturedOutput output) throws Exception {
        long orderId = idOf(json(postOrder(newKey(), orderJson(createProduct("TRACE-2", 100_000, 10), 1))));
        int from = output.getOut().length();

        HttpResponse<String> r = get("/api/orders/" + orderId, null);

        String traceId = r.headers().firstValue(TraceIdFilter.HEADER).orElseThrow();
        assertThat(traceId).matches("[0-9a-f]{16}");
        assertThat(output.getOut().substring(from)).contains("[" + traceId + "]");
    }

    @Test
    @DisplayName("X-Trace-Id không hợp lệ (dấu cách, quá 64 ký tự) → bỏ, tạo mã mới: log không chứa chuỗi lạ client gửi")
    void invalidHeader_isReplaced() throws Exception {
        for (String bad : List.of("abc def", "x".repeat(65), "<script>")) {
            HttpResponse<String> r = get("/api/products?size=1", bad);

            assertThat(r.headers().firstValue(TraceIdFilter.HEADER)).hasValueSatisfying(id -> assertThat(id).matches("[0-9a-f]{16}"));
        }
    }

    private HttpResponse<String> get(String path, String traceId) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).GET();
        if (traceId != null) {
            request.header(TraceIdFilter.HEADER, traceId);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
