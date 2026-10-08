package com.shoplab.common;

import org.springframework.http.HttpStatus;

/**
 * Client sửa dựa trên một version cũ: bản ghi đã bị sửa sau lần client đọc → 409, không ghi đè.
 * Cùng type concurrent-modification với lỗi @Version của Hibernate, kèm thêm version hiện tại để client tải lại.
 *
 * @Version không tự phát hiện trường hợp này: mỗi request đọc lại bản ghi ở version mới nhất rồi mới sửa,
 * nên service phải so version client gửi lên với version vừa đọc (xem {@link #check}).
 */
public class StaleVersionException extends ApiException {

    /** @param resource tên bản ghi trong thông báo lỗi, vd "Sản phẩm", "Hồ sơ người dùng" */
    public StaleVersionException(String resource, Long id, long expectedVersion, long currentVersion) {
        super(HttpStatus.CONFLICT, "concurrent-modification", "Concurrent Modification",
                resource + " id = " + id + " đã bị sửa sau lần bạn đọc (bạn gửi version " + expectedVersion
                        + ", hiện tại là " + currentVersion + "), hãy tải lại rồi sửa lại");
        addProperty("expectedVersion", expectedVersion);
        addProperty("currentVersion", currentVersion);
    }

    /** Ném lỗi nếu version client đã đọc khác version hiện tại của entity. */
    public static void check(String resource, AuditedEntity entity, long expectedVersion) {
        if (entity.getVersion() != expectedVersion) {
            throw new StaleVersionException(resource, entity.getId(), expectedVersion, entity.getVersion());
        }
    }
}
