package com.shoplab.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trả MỌI lỗi theo chuẩn ProblemDetail (RFC 9457, Content-Type: application/problem+json).
 *
 *  1. Lỗi nghiệp vụ của các module  → ApiException (mỗi lỗi tự mang status, type, title)
 *  2. Lỗi khoá / DB / dữ liệu       → 400 / 409
 *  3. Lỗi có sẵn của Spring MVC     → kế thừa ResponseEntityExceptionHandler
 *     (JSON sai, thiếu header, sai kiểu tham số, 404 path, 405, 415...)
 *  4. Lỗi không lường trước (500)   → catch-all Exception
 *
 * Class này không import exception của module nào: thêm lỗi mới chỉ cần kế thừa ApiException.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://shoplab.dev/errors/";
    private static final String PG_LOCK_NOT_AVAILABLE = "55P03";   // lock_not_available (vượt quá lock_timeout)


    // ===================== 1. LỖI NGHIỆP VỤ =====================

    /** Lỗi nghiệp vụ của mọi module (product, order, idempotency...) đều kế thừa ApiException. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        ProblemDetail pd = problem(ex.getStatus(), ex.getType(), ex.getTitle(), ex.getMessage());
        ex.getProperties().forEach(pd::setProperty);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        ex.getHeaders().forEach((name, value) -> response.header(name, value));
        return response.body(pd);
    }

    // ===================== 2. LỖI KHOÁ / DB / DỮ LIỆU =====================

    /**
     * Chờ khoá quá lock_timeout: request trùng key đang chạy quá lâu,
     * hoặc sản phẩm đang bị đơn khác khoá → 409 + Retry-After, client gửi lại với CÙNG key.
     * Lỗi có thể tới theo 2 đường:
     *  - qua JPA/Hibernate → PessimisticLockingFailureException
     *  - qua JdbcClient    → UncategorizedSQLException (Spring không phân loại mã 55P03)
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleLockTimeout(PessimisticLockingFailureException ex) {
        return lockTimeoutResponse();
    }

    @ExceptionHandler(UncategorizedSQLException.class)
    public ResponseEntity<ProblemDetail> handleUncategorizedSql(UncategorizedSQLException ex) {
        if (ex.getSQLException() != null
                && PG_LOCK_NOT_AVAILABLE.equals(ex.getSQLException().getSQLState())) {
            return lockTimeoutResponse();
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(handleUnexpected(ex));    // lỗi SQL khác → vẫn là 500 như cũ
    }

    private static ResponseEntity<ProblemDetail> lockTimeoutResponse() {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "lock-timeout", "Request In Progress",
                "Hệ thống đang xử lý một request khác trên cùng dữ liệu, hãy thử lại sau giây lát");
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(pd);
    }

    /**
     * Vi phạm ràng buộc DB mà service sở hữu dữ liệu chưa dịch sang lỗi nghiệp vụ.
     * Các trường hợp đã biết (trùng SKU, xoá sản phẩm đã có trong đơn...) được dịch ngay trong service.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "data-integrity", "Data Integrity Violation",
                "Thao tác vi phạm ràng buộc dữ liệu");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "concurrent-modification", "Concurrent Modification",
                "Dữ liệu vừa bị thay đổi bởi request khác, hãy tải lại và thử lại");
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail handleBadSort(PropertyReferenceException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-sort", "Invalid Sort Property",
                "Không thể sắp xếp theo trường '" + ex.getPropertyName() + "'");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));

        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation", "Validation Failed",
                "Dữ liệu gửi lên không hợp lệ");
        pd.setProperty("errors", errors);
        return pd;
    }

    // ===================== 3. LỖI CÓ SẴN CỦA SPRING MVC =====================

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(),
                    fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "không hợp lệ");
        }

        ProblemDetail pd = ex.getBody();
        pd.setType(URI.create(TYPE_BASE + "validation"));
        pd.setTitle("Validation Failed");
        pd.setDetail("Dữ liệu gửi lên không hợp lệ");
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    /** Mọi lỗi Spring MVC xử lý sẵn đều đi qua đây → gắn thêm timestamp cho đồng bộ. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        ResponseEntity<Object> response =
                super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd) {
            pd.setProperty("timestamp", Instant.now());
        }
        return response;
    }

    // ===================== 4. CATCH-ALL (500) =====================

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Internal Server Error",
                "Đã có lỗi xảy ra, vui lòng thử lại sau");
    }

    // ===================== HELPER =====================

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + type));
        pd.setTitle(title);
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
