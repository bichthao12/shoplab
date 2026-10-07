package com.shoplab.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit test cho mã băm request (không cần Spring, không cần DB). */
class RequestFingerprintTest {

    record Sample(String customerName, String customerEmail, Map<Long, Integer> quantities) {}

    @Test
    @DisplayName("Cùng nội dung → cùng mã băm, thứ tự thêm vào map không ảnh hưởng")
    void sameContent_sameHash() {
        Map<Long, Integer> a = new LinkedHashMap<>();
        a.put(2L, 1);
        a.put(1L, 2);
        Map<Long, Integer> b = new LinkedHashMap<>();
        b.put(1L, 2);
        b.put(2L, 1);

        assertThat(RequestFingerprint.of(new Sample("A", "a@example.com", a)))
                .isEqualTo(RequestFingerprint.of(new Sample("A", "a@example.com", b)));
    }

    @Test
    @DisplayName("Dấu | trong giá trị không làm hai request khác nhau trùng mã băm (lỗi của cách ghép chuỗi trước đây)")
    void separatorInsideValues_doesNotCollide() {
        String first = RequestFingerprint.of(new Sample("A|b", "c@example.com", Map.of(4L, 1)));
        String second = RequestFingerprint.of(new Sample("A", "b|c@example.com", Map.of(4L, 1)));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("Mã băm là SHA-256 dạng hex (64 ký tự)")
    void hashIsSha256Hex() {
        assertThat(RequestFingerprint.of(new Sample("A", "a@example.com", Map.of())))
                .matches("[0-9a-f]{64}");
    }
}
