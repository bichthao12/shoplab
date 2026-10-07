package com.shoplab.idempotency;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Idempotency trong MỘT transaction duy nhất:
 *
 *   BEGIN
 *     INSERT key (IN_PROGRESS) ON CONFLICT DO NOTHING   ← request trùng key sẽ CHỜ ở đây (tối đa lock_timeout)
 *     ... nghiệp vụ: khoá product, trừ kho, tạo order ...
 *     UPDATE key → COMPLETED + status, header, body của response
 *   COMMIT
 *
 * Hệ quả:
 *  - Key và đơn hàng được ghi cùng nhau: không bao giờ có đơn mà thiếu key, hay key mà thiếu đơn.
 *  - Nghiệp vụ lỗi → ROLLBACK cả key lẫn đơn → client gửi lại cùng key được, không cần "nhả key".
 *  - Request trùng key đến cùng lúc: request sau chờ request trước commit rồi nhận bản replay.
 *    Chờ quá lock_timeout (cấu hình chung cho mọi connection) → 409 + Retry-After.
 *  - Response được lưu NGUYÊN VĂN (chuỗi JSON + header) và trả lại y hệt, không đọc ngược vào class DTO:
 *    đổi DTO sau này không làm hỏng bản replay của các key đang còn hạn.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,255}$");
    private static final TypeReference<Map<String, List<String>>> HEADERS_TYPE = new TypeReference<>() {};

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * @param key     giá trị header Idempotency-Key
     * @param request request ở dạng chuẩn hoá (cùng nội dung → cùng giá trị), dùng để phát hiện key bị dùng lại
     * @param action  nghiệp vụ (tham gia CHUNG transaction này), trả về response lần đầu
     * @return response lần đầu; gửi lại cùng key → bản lưu y hệt, kèm header Idempotent-Replayed: true
     */
    @Transactional
    public ResponseEntity<String> execute(String key, Object request, Supplier<ResponseEntity<?>> action) {
        validateKey(key);
        String requestHash = RequestFingerprint.of(request);

        if (!store.tryClaim(key, requestHash)) {
            // Chỉ tới được đây khi transaction giữ key trước đó đã COMMIT
            IdempotencyRecord existing = store.find(key)
                    .orElseThrow(() -> new IdempotencyInProgressException(key));

            if (!existing.requestHash().equals(requestHash)) {
                throw new IdempotencyKeyReusedException(key);
            }
            if (!existing.isCompleted()) {   // phòng thủ – về lý thuyết không xảy ra
                throw new IdempotencyInProgressException(key);
            }
            Map<String, List<String>> headers = existing.responseHeaders() == null
                    ? Map.of()
                    : objectMapper.readValue(existing.responseHeaders(), HEADERS_TYPE);
            return toResponse(key, existing.responseStatus(), headers, existing.responseBody(), true);
        }

        ResponseEntity<?> response = action.get();   // lỗi ở đây → exception bay ra → rollback cả key lẫn đơn

        int status = response.getStatusCode().value();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.getHeaders().forEach(headers::put);
        String body = objectMapper.writeValueAsString(response.getBody());

        store.complete(key, status, objectMapper.writeValueAsString(headers), body);
        return toResponse(key, status, headers, body, false);
    }

    /** Lần đầu và các lần replay dựng response từ CÙNG dữ liệu, nên giống nhau từng byte. */
    private static ResponseEntity<String> toResponse(String key, int status, Map<String, List<String>> headers,
                                                     String body, boolean replayed) {
        HttpHeaders out = new HttpHeaders();
        headers.forEach(out::addAll);
        if (out.getContentType() == null) {
            out.setContentType(MediaType.APPLICATION_JSON);
        }
        out.set(HEADER, key);
        if (replayed) {
            out.set(REPLAYED_HEADER, "true");
        }
        return ResponseEntity.status(status).headers(out).body(body);
    }

    private static void validateKey(String key) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
    }
}
