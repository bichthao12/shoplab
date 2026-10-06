package com.shoplab.idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Idempotency trong MỘT transaction duy nhất:
 *
 *   BEGIN
 *     SET LOCAL lock_timeout = '5s'
 *     INSERT key (IN_PROGRESS) ON CONFLICT DO NOTHING   ← request trùng key sẽ CHỜ ở đây
 *     ... nghiệp vụ: khoá product, trừ kho, tạo order ...
 *     UPDATE key → COMPLETED + response_status + response_body
 *   COMMIT
 *
 * Hệ quả:
 *  - Key và đơn hàng được ghi cùng nhau: không bao giờ có đơn mà thiếu key, hay key mà thiếu đơn.
 *  - Nghiệp vụ lỗi → ROLLBACK cả key lẫn đơn → client gửi lại cùng key được, không cần "nhả key".
 *  - Request trùng key đến cùng lúc: request sau chờ request trước commit rồi nhận bản replay.
 *    Chờ quá lock_timeout → 409 + Retry-After.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,255}$");
    private static final int LOCK_TIMEOUT_SECONDS = 5;

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * @param key           giá trị header Idempotency-Key
     * @param requestHash   mã băm của request đã chuẩn hoá
     * @param successStatus mã phản hồi khi thành công (vd 201)
     * @param responseType  kiểu của body, để đọc lại từ JSON khi replay
     * @param action        nghiệp vụ (tham gia CHUNG transaction này), trả về body phản hồi
     */
    @Transactional
    public <T> IdempotentResult<T> execute(String key, String requestHash, int successStatus,
                                           Class<T> responseType, Supplier<T> action) {
        validateKey(key);
        store.setLockTimeout(LOCK_TIMEOUT_SECONDS);

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
            T body = objectMapper.readValue(existing.responseBody(), responseType);
            return new IdempotentResult<>(body, existing.responseStatus(), true);
        }

        T body = action.get();   // lỗi ở đây → exception bay ra → rollback cả key lẫn đơn

        store.complete(key, successStatus, objectMapper.writeValueAsString(body));
        return new IdempotentResult<>(body, successStatus, false);
    }

    private static void validateKey(String key) {
        if (key == null || !KEY_FORMAT.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
    }
}
