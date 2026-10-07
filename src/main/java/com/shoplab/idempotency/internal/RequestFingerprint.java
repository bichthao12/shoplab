package com.shoplab.idempotency.internal;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * "Dấu vân tay" của request: SHA-256 của JSON dạng chuẩn hoá.
 *  - Băm JSON (có cấu trúc, ký tự đặc biệt được escape) thay vì tự ghép chuỗi bằng dấu phân cách,
 *    nên hai request khác nhau không thể ra cùng một chuỗi đầu vào.
 *  - Dùng mapper riêng, cấu hình cố định (sắp thứ tự field và key của map): đổi cấu hình Jackson
 *    của app không làm đổi mã băm của các key đang còn hạn.
 */
final class RequestFingerprint {

    private static final JsonMapper CANONICAL_JSON = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private RequestFingerprint() {
    }

    /** @param canonicalRequest request ở dạng chuẩn hoá: cùng nội dung → cùng giá trị */
    static String of(Object canonicalRequest) {
        byte[] json = CANONICAL_JSON.writeValueAsBytes(canonicalRequest);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
