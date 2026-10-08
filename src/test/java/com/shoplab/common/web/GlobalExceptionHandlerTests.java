package com.shoplab.common.web;

import com.shoplab.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test slice cho tầng web: chỉ dựng Spring MVC + GlobalExceptionHandler, không DB, không service.
 * Một controller giả ném lỗi để kiểm tra cách lỗi được trả về dạng ProblemDetail.
 */
@WebMvcTest(GlobalExceptionHandlerTests.ThrowingController.class)
@Import(GlobalExceptionHandlerTests.ThrowingController.class)
class GlobalExceptionHandlerTests {

    @Autowired MockMvc mvc;

    @Test
    @DisplayName("ApiException → ProblemDetail dựng từ thông tin lỗi tự mang (status, type, title, trường mở rộng, header)")
    void apiException_isRenderedFromItsOwnInfo() throws Exception {
        mvc.perform(get("/test/api-exception"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.type").value("https://shoplab.dev/errors/sample"))
                .andExpect(jsonPath("$.title").value("Sample Error"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Chi tiết lỗi"))
                .andExpect(jsonPath("$.instance").value("/test/api-exception"))
                .andExpect(jsonPath("$.sku").value("AO-1"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Lỗi ràng buộc DB mà service chưa dịch → 409 data-integrity với câu báo chung")
    void untranslatedDataIntegrityViolation_returnsGeneric409() throws Exception {
        mvc.perform(get("/test/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://shoplab.dev/errors/data-integrity"))
                .andExpect(jsonPath("$.detail").value("Thao tác vi phạm ràng buộc dữ liệu"));
    }

    @Test
    @DisplayName("Deadlock (40P01) → 409 deadlock + Retry-After; lỗi khoá khác vẫn là 409 lock-timeout")
    void deadlock_isReportedSeparatelyFromLockTimeout() throws Exception {
        mvc.perform(get("/test/deadlock"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.type").value("https://shoplab.dev/errors/deadlock"))
                .andExpect(jsonPath("$.title").value("Deadlock Detected"));

        mvc.perform(get("/test/lock-timeout"))
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.type").value("https://shoplab.dev/errors/lock-timeout"));
    }

    @Test
    @DisplayName("Lỗi không lường trước → 500, không lộ chi tiết bên trong")
    void unexpectedError_returns500WithoutInternalDetail() throws Exception {
        mvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://shoplab.dev/errors/internal"))
                .andExpect(jsonPath("$.detail").value("Đã có lỗi xảy ra, vui lòng thử lại sau"))
                .andExpect(content().string(not(containsString("bí mật"))));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/api-exception")
        void apiException() {
            throw new SampleException();
        }

        @GetMapping("/test/data-integrity")
        void dataIntegrity() {
            throw new DataIntegrityViolationException("duplicate key value violates unique constraint");
        }

        @GetMapping("/test/deadlock")
        void deadlock() {
            throw new CannotAcquireLockException("could not execute statement",
                    new SQLException("ERROR: deadlock detected", "40P01"));
        }

        @GetMapping("/test/lock-timeout")
        void lockTimeout() {
            throw new CannotAcquireLockException("could not execute statement",
                    new SQLException("ERROR: canceling statement due to lock timeout", "55P03"));
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("chi tiết bí mật");
        }
    }

    static class SampleException extends ApiException {
        SampleException() {
            super(HttpStatus.CONFLICT, "sample", "Sample Error", "Chi tiết lỗi");
            addProperty("sku", "AO-1");
            addHeader(HttpHeaders.RETRY_AFTER, "1");
        }
    }
}
