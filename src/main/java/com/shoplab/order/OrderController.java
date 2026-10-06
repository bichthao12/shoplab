package com.shoplab.order;

import com.shoplab.idempotency.IdempotencyService;
import com.shoplab.idempotency.IdempotentResult;
import com.shoplab.order.dto.CreateOrderRequest;
import com.shoplab.order.dto.OrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
     *  - Gửi lại cùng key, cùng body → trả lại NGUYÊN VĂN response lần đầu, header Idempotent-Replayed: true
     *  - Cùng key, body khác         → 422
     *  - Cùng key, request trước đang chạy → 409 + Retry-After
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest req) {

        IdempotentResult<OrderResponse> result = idempotency.execute(
                idempotencyKey,
                req.fingerprint(),
                HttpStatus.CREATED.value(),
                OrderResponse.class,
                () -> orderService.create(req));

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.body().id())
                .toUri();

        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status())
                .location(location)
                .header(IdempotencyService.HEADER, idempotencyKey);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable Long id) {
        return orderService.getById(id);
    }
}
