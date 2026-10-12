package com.shoplab.order.web;

import com.shoplab.idempotency.IdempotencyService;
import com.shoplab.order.internal.CreateOrderCommand;
import com.shoplab.order.internal.OrderPosition;
import com.shoplab.order.internal.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    static final int MAX_PAGE_SIZE = 100;

    private final OrderService orderService;
    private final IdempotencyService idempotency;

    public OrderController(OrderService orderService, IdempotencyService idempotency) {
        this.orderService = orderService;
        this.idempotency = idempotency;
    }

    /**
     * POST /api/orders – BẮT BUỘC header Idempotency-Key.
     *  - Thiếu header                → 400
     *  - Lần đầu                     → 201 + Location
     *  - Gửi lại cùng key, cùng body → trả lại NGUYÊN VĂN response lần đầu (status, header, body),
     *                                  kèm header Idempotent-Replayed: true
     *  - Cùng key, body khác         → 422
     *  - Cùng key, request trước đang chạy → 409 + Retry-After
     * Controller chỉ mô tả response lần đầu; lưu và trả lại bản lưu là việc của IdempotencyService.
     */
    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest req) {

        CreateOrderCommand command = req.toCommand();
        return idempotency.execute(idempotencyKey, command, () -> {
            OrderResponse created = OrderResponse.from(orderService.create(command));
            URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                    .path("/{id}")
                    .buildAndExpand(created.id())
                    .toUri();
            return ResponseEntity.created(location).body(created);
        });
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return OrderResponse.from(orderService.getById(id));
    }

    /**
     * GET /api/orders?userId=1&size=20&cursor=... → danh sách đơn, mới nhất trước, phân trang cursor:
     * {"content": [...], "nextCursor": "..."}. Trang sau: gửi lại nextCursor ở tham số cursor; nextCursor null là hết.
     *  - userId: chỉ đơn của người dùng này; bỏ trống: mọi đơn
     *  - size: 1..100, mặc định 20
     * Chỉ trả thông tin tóm tắt (không kèm dòng hàng, xem chi tiết ở GET /api/orders/{id}). Không có tổng số đơn, số
     * trang, và không nhảy tới trang số N: đổi lại trang thứ 10.000 nhanh như trang đầu (OrderService.listOrders).
     * Cursor hỏng → 400 invalid-cursor; gửi page / sort (tham số của phân trang offset trước đây) cũng 400, vì bỏ qua
     * thì client tưởng đang xem trang N mà thật ra luôn nhận trang đầu.
     */
    @GetMapping
    public OrderListResponse list(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String sort) {
        if (page != null || sort != null) {
            throw new InvalidCursorException("Danh sách đơn phân trang bằng cursor, luôn mới nhất trước: bỏ page, sort; "
                    + "lấy trang sau bằng tham số cursor = nextCursor của trang trước");
        }
        OrderPosition after = cursor == null ? null : OrderCursor.decode(cursor);
        return OrderListResponse.from(orderService.listOrders(userId, after, size));
    }

    /**
     * GET /api/orders/with-items?userId=1&page=0&size=100 → đơn của một người dùng, mới nhất trước, mỗi đơn kèm dòng
     * hàng (cùng dạng GET /api/orders/{id}). Trang bao nhiêu đơn cũng chỉ 1 câu SQL, cộng câu đếm khi trang đầy
     * (OrderService.listByUserWithItems). Vẫn phân trang offset (page, có tổng số đơn): API của bài N+1, một người
     * dùng chỉ có vài trăm tới vài nghìn đơn nên OFFSET không đáng kể; danh sách chính là GET /api/orders ở trên.
     */
    @GetMapping("/with-items")
    public PagedModel<OrderResponse> listByUserWithItems(
            @RequestParam long userId,
            @PageableDefault(size = 20)
            @SortDefault.SortDefaults({
                    @SortDefault(sort = "createdAt", direction = Sort.Direction.DESC),
                    @SortDefault(sort = "id", direction = Sort.Direction.DESC)
            }) Pageable pageable) {
        return new PagedModel<>(orderService.listByUserWithItems(userId, pageable).map(OrderResponse::from));
    }
}
