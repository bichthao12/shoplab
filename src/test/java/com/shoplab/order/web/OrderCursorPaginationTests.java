package com.shoplab.order.web;

import com.shoplab.IntegrationTestBase;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /api/orders phân trang cursor (keyset): trang sau bắt đầu ngay sau đơn cuối của trang trước, theo thứ tự
 * (created_at DESC, id DESC). So sánh tốc độ với OFFSET trên 10 triệu đơn: scripts/pagination-lab.sql,
 * scripts/cursor-pagination.ps1. Bản lỗi cursor chỉ so created_at (bỏ sót đơn trùng created_at ở ranh giới trang):
 * scripts/bugs/14-cursor-ties.patch.
 */
class OrderCursorPaginationTests extends IntegrationTestBase {

    @Test
    @DisplayName("Đi theo nextCursor qua mọi trang: mỗi đơn đúng một lần, mới nhất trước; trang cuối nextCursor null")
    void walkAllPages_eachOrderOnceNewestFirst() {
        createOrdersWithItems(customerId(), 25);
        createOrdersWithItems(createUser("b@example.com", "Trần Thị B", "b.tran", "ACTIVE"), 20);

        List<Map<String, Object>> pages = walk("/api/orders?size=20");

        assertThat(pages).extracting(page -> ids(page).size()).containsExactly(20, 20, 5);
        assertThat(pages.stream().flatMap(page -> ids(page).stream()).toList())
                .containsExactlyElementsOf(idsNewestFirst("true"));
        assertThat(pages.getLast()).containsEntry("nextCursor", null);
    }

    @Test
    @DisplayName("Không gửi size → 20 đơn; size=100 → 100 đơn")
    void defaultAndMaximumSize() {
        createOrdersWithItems(customerId(), 120);

        assertThat(ids(get("/api/orders"))).hasSize(20);
        assertThat(ids(get("/api/orders?size=100"))).hasSize(100);
    }

    @Test
    @DisplayName("Đơn mới đặt giữa hai lần gọi: trang 2 theo cursor là đúng 10 đơn kế tiếp, không lặp đơn nào (OFFSET thì lặp)")
    void newOrderBetweenPages_noDuplicates() {
        createOrdersWithItems(customerId(), 30);
        List<Long> before = idsNewestFirst("true");
        Map<String, Object> first = get("/api/orders?size=10");

        long productId = createProduct("CURSOR-001", 100_000, 10);
        assertThat(postOrder(newKey(), orderJson(productId, 1)).statusCode()).isEqualTo(201);
        Map<String, Object> second = get("/api/orders?size=10&cursor=" + first.get("nextCursor"));

        assertThat(ids(first)).containsExactlyElementsOf(before.subList(0, 10));
        assertThat(ids(second)).containsExactlyElementsOf(before.subList(10, 20));
        // Cùng lúc đó OFFSET 10: đơn mới đẩy mọi đơn lùi một chỗ, trang 2 mở đầu bằng đơn cuối của trang 1
        List<Long> offsetSecond = jdbc.sql("SELECT id FROM orders ORDER BY created_at DESC, id DESC OFFSET 10 LIMIT 10")
                .query(Long.class).list();
        assertThat(offsetSecond.getFirst()).isEqualTo(ids(first).getLast());
    }

    @Test
    @DisplayName("6 đơn cùng created_at, ranh giới trang rơi giữa chúng: không sót, không lặp (cursor so cả id)")
    void ordersWithSameCreatedAt_acrossPageBoundary() {
        createOrdersWithItems(customerId(), 10);
        jdbc.sql("""
                UPDATE orders SET created_at = timestamptz '2026-01-01 00:00:00+00'
                WHERE id IN (SELECT id FROM orders ORDER BY id OFFSET 2 LIMIT 6)
                """).update();

        // 10 đơn, trang 4 đơn: 6 đơn trùng created_at là đơn thứ 5 – 10, ranh giới trang 2 / 3 nằm giữa chúng
        assertThat(walk("/api/orders?size=4").stream().flatMap(page -> ids(page).stream()).toList())
                .containsExactlyElementsOf(idsNewestFirst("true"));
        assertThat(walk("/api/orders?userId=" + customerId() + "&size=4").stream().flatMap(page -> ids(page).stream()).toList())
                .containsExactlyElementsOf(idsNewestFirst("user_id = " + customerId()));
    }

    @Test
    @DisplayName("cursor hỏng → 400 invalid-cursor")
    void invalidCursor_returns400() {
        List<String> cursors = List.of(
                "",
                "khong-phai-base64!",
                "x".repeat(101),
                base64url("2026-01-01T00:00:00Z"),              // thiếu id
                base64url("2026-01-01T00:00:00Z,abc"),          // id không phải số
                base64url("2026-01-01T00:00:00Z,-1"),           // id âm
                base64url("hôm qua,5"));                        // thời điểm sai dạng
        for (String cursor : cursors) {
            var r = send("GET", "/api/orders?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8), null, null);
            assertThat(r.statusCode()).as("cursor \"%s\": %s", cursor, r.body()).isEqualTo(400);
            assertProblem(r, 400, "invalid-cursor");
        }
    }

    @Test
    @DisplayName("Gửi page / sort (phân trang offset cũ) → 400 invalid-cursor; size ngoài 1..100 → 400 validation")
    void offsetParametersAndSizeOutOfRange_return400() {
        assertProblem(send("GET", "/api/orders?page=1", null, null), 400, "invalid-cursor");
        assertProblem(send("GET", "/api/orders?sort=id,desc", null, null), 400, "invalid-cursor");

        for (String size : List.of("0", "101")) {
            var r = send("GET", "/api/orders?size=" + size, null, null);
            assertProblem(r, 400, "validation");
            assertThat(json(r).get("errors")).asInstanceOf(InstanceOfAssertFactories.MAP).containsOnlyKeys("size");
        }
    }

    // ---------- helper ----------

    /** Gọi trang đầu rồi theo nextCursor tới hết; trả về mọi trang. */
    private List<Map<String, Object>> walk(String firstPage) {
        List<Map<String, Object>> pages = new ArrayList<>();
        Map<String, Object> page = get(firstPage);
        pages.add(page);
        while (page.get("nextCursor") != null) {
            assertThat(pages).as("số trang").hasSizeLessThan(100);
            page = get(firstPage + "&cursor=" + page.get("nextCursor"));
            pages.add(page);
        }
        return pages;
    }

    private Map<String, Object> get(String path) {
        var r = send("GET", path, null, null);
        assertThat(r.statusCode()).as("GET %s: %s", path, r.body()).isEqualTo(200);
        return json(r);
    }

    private List<Long> idsNewestFirst(String where) {
        return jdbc.sql("SELECT id FROM orders WHERE " + where + " ORDER BY created_at DESC, id DESC")
                .query(Long.class).list();
    }

    @SuppressWarnings("unchecked")
    private static List<Long> ids(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("content")).stream()
                .map(order -> ((Number) order.get("id")).longValue()).toList();
    }

    private static String base64url(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
