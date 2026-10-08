package com.shoplab.product.web;

import com.shoplab.idempotency.IdempotencyService;
import com.shoplab.product.internal.AdjustStockCommand;
import com.shoplab.product.internal.ProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/** Tầng HTTP của sản phẩm: đổi DTO web ↔ command / entity, còn nghiệp vụ nằm ở ProductService. */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;
    private final IdempotencyService idempotency;

    public ProductController(ProductService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    /** POST /api/products → 201 Created + header Location */
    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest req) {
        ProductResponse created = ProductResponse.from(service.create(req.toCommand()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** GET /api/products/{id} → 200 hoặc 404 */
    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return ProductResponse.from(service.getById(id));
    }

    /** GET /api/products?category=ao&page=0&size=20&sort=price,asc */
    @GetMapping
    public PagedModel<ProductResponse> list(
            @RequestParam(required = false) String category,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(service.list(category, pageable).map(ProductResponse::from));
    }

    /** PATCH /api/products/{id} → 200 với dữ liệu mới */
    @PatchMapping("/{id}")
    public ProductResponse patch(@PathVariable Long id, @Valid @RequestBody PatchProductRequest req) {
        return ProductResponse.from(service.update(id, req.toCommand()));
    }

    /**
     * POST /api/products/{id}/stock-adjustments (bắt buộc Idempotency-Key) → 200 với sản phẩm sau khi điều chỉnh.
     * Body {"delta": 5} cộng 5, {"delta": -2} trừ 2. Trừ quá tồn kho → 409 insufficient-stock, kho giữ nguyên.
     * Gửi lại cùng key (vd sau khi mạng chập chờn) → nhận lại response lần đầu, kho không bị cộng / trừ lần nữa.
     */
    @PostMapping("/{id}/stock-adjustments")
    public ResponseEntity<String> adjustStock(
            @RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
            @PathVariable long id,
            @Valid @RequestBody StockAdjustmentRequest req) {

        AdjustStockCommand command = req.toCommand(id);
        return idempotency.execute(idempotencyKey, command,
                () -> ResponseEntity.ok(ProductResponse.from(service.adjustStock(command))));
    }

    /** DELETE /api/products/{id} → 204 No Content */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
