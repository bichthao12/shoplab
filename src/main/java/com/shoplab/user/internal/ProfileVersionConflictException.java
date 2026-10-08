package com.shoplab.user.internal;

import com.shoplab.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Client sửa hồ sơ dựa trên một version cũ: đã có người sửa sau lần client đọc → 409, không ghi đè.
 * Cùng type concurrent-modification với lỗi @Version của Hibernate, kèm thêm version hiện tại để client tải lại.
 */
class ProfileVersionConflictException extends ApiException {
    ProfileVersionConflictException(Long userId, long expectedVersion, long currentVersion) {
        super(HttpStatus.CONFLICT, "concurrent-modification", "Concurrent Modification",
                "Hồ sơ người dùng id = " + userId + " đã bị sửa sau lần bạn đọc (bạn gửi version " + expectedVersion
                        + ", hiện tại là " + currentVersion + "), hãy tải lại rồi sửa lại");
        addProperty("expectedVersion", expectedVersion);
        addProperty("currentVersion", currentVersion);
    }
}
