package com.shoplab.order.web;

import com.shoplab.idempotency.IdempotencyService;
import com.shoplab.order.internal.CreateOrderCommand;
import com.shoplab.order.internal.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

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
}
