package com.shoplab.common;

import com.shoplab.idempotency.IdempotencyInProgressException;
import com.shoplab.idempotency.IdempotencyKeyReusedException;
import com.shoplab.idempotency.InvalidIdempotencyKeyException;
import com.shoplab.order.InsufficientStockException;
import com.shoplab.order.InvalidOrderException;
import com.shoplab.order.OrderNotFoundException;
import com.shoplab.product.DuplicateSkuException;
import com.shoplab.product.ProductNotFoundException;
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
 *  1. Lỗi nghiệp vụ                 → @ExceptionHandler riêng
 *  2. Lỗi idempotency               → 400 / 409 / 422
 *  3. Lỗi DB / dữ liệu              → 400 / 409
 *  4. Lỗi có sẵn của Spring MVC     → kế thừa ResponseEntityExceptionHandler
 *     (JSON sai, thiếu header, sai kiểu tham số, 404 path, 405, 415...)
 *  5. Lỗi không lường trước (500)   → catch-all Exception
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://shoplab.dev/errors/";
    private static final String PG_LOCK_NOT_AVAILABLE = "55P03";


    // ===================== 1. LỖI NGHIỆP VỤ =====================

    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail handleProductNotFound(ProductNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "product-not-found", "Product Not Found", ex.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "order-not-found", "Order Not Found", ex.getMessage());
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ProblemDetail handleDuplicateSku(DuplicateSkuException ex) {
        return problem(HttpStatus.CONFLICT, "duplicate-sku", "Duplicate SKU", ex.getMessage());
    }

    @ExceptionHandler(InvalidOrderException.class)
    public ProblemDetail handleInvalidOrder(InvalidOrderException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-order", "Invalid Order", ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "insufficient-stock", "Insufficient Stock", ex.getMessage());
        pd.setProperty("sku", ex.getSku());
        pd.setProperty("requested", ex.getRequested());
        pd.setProperty("available", ex.getAvailable());
        return pd;
    }

    // ===================== 2. LỖI IDEMPOTENCY =====================

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ProblemDetail handleInvalidKey(InvalidIdempotencyKeyException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-idempotency-key", "Invalid Idempotency-Key", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ProblemDetail handleKeyReused(IdempotencyKeyReusedException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reused", "Idempotency Key Reused", ex.getMessage());
    }

    /** 409 + Retry-After: báo client đợi 1 giây rồi gửi lại với CÙNG key. */
    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<ProblemDetail> handleInProgress(IdempotencyInProgressException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "idempotency-in-progress", "Request In Progress", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(pd);
    }

    /** Postgres SQLState 55P03 = lock_not_available (vượt quá lock_timeout). */

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

    // ===================== 3. LỖI DB / DỮ LIỆU =====================

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "data-integrity", "Data Integrity Violation",
                "Thao tác vi phạm ràng buộc dữ liệu (ví dụ: sản phẩm đang nằm trong đơn hàng)");
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

    // ===================== 4. LỖI CÓ SẴN CỦA SPRING MVC =====================

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

    // ===================== 5. CATCH-ALL (500) =====================

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
