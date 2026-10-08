package com.shoplab.wallet.web;

import com.shoplab.idempotency.IdempotencyService;
import com.shoplab.wallet.internal.DepositCommand;
import com.shoplab.wallet.internal.TransferCommand;
import com.shoplab.wallet.internal.WalletService;
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

/**
 * Tầng HTTP của ví: đổi DTO web ↔ command / entity, còn nghiệp vụ nằm ở WalletService.
 * Nạp và chuyển tiền bắt buộc header Idempotency-Key (giống POST /api/orders): gửi lại cùng key thì nhận lại
 * nguyên văn response lần đầu, tiền không bị nạp / chuyển hai lần.
 */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final WalletService service;
    private final IdempotencyService idempotency;

    public WalletController(WalletService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    /** POST /api/wallets → 201 Created + Location; người dùng đã có ví → 409 */
    @PostMapping
    public ResponseEntity<WalletResponse> create(@Valid @RequestBody CreateWalletRequest req) {
        WalletResponse created = WalletResponse.from(service.create(req.userId()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** GET /api/wallets/{id} → 200 hoặc 404 */
    @GetMapping("/{id}")
    public WalletResponse get(@PathVariable long id) {
        return WalletResponse.from(service.getById(id));
    }

    /** POST /api/wallets/{id}/deposits (bắt buộc Idempotency-Key) → 200 với ví sau khi nạp */
    @PostMapping("/{id}/deposits")
    public ResponseEntity<String> deposit(
            @RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
            @PathVariable long id,
            @Valid @RequestBody DepositRequest req) {

        DepositCommand command = req.toCommand(id);
        return idempotency.execute(idempotencyKey, command,
                () -> ResponseEntity.ok(WalletResponse.from(service.deposit(command))));
    }

    /** POST /api/wallets/transfers (bắt buộc Idempotency-Key) → 200 với hai ví sau khi chuyển */
    @PostMapping("/transfers")
    public ResponseEntity<String> transfer(
            @RequestHeader(IdempotencyService.HEADER) String idempotencyKey,
            @Valid @RequestBody TransferRequest req) {

        TransferCommand command = req.toCommand();
        return idempotency.execute(idempotencyKey, command,
                () -> ResponseEntity.ok(TransferResponse.from(service.transfer(command))));
    }
}
