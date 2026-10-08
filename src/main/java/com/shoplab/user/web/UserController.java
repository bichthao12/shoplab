package com.shoplab.user.web;

import com.shoplab.user.internal.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/** Tầng HTTP của người dùng: đổi DTO web ↔ command / entity, còn nghiệp vụ nằm ở UserService. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    /** POST /api/users → 201 Created + header Location */
    @PostMapping
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterUserRequest req) {
        UserResponse created = UserResponse.from(service.register(req.toCommand()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** GET /api/users/{id} → 200 hoặc 404 */
    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        return UserResponse.from(service.getById(id));
    }

    /** PATCH /api/users/{id} → 200 với hồ sơ mới */
    @PatchMapping("/{id}")
    public UserResponse updateProfile(@PathVariable Long id, @Valid @RequestBody UpdateProfileRequest req) {
        return UserResponse.from(service.updateProfile(id, req.toCommand()));
    }

    /** POST /api/users/{id}/lock → 200; đã khoá rồi thì giữ nguyên */
    @PostMapping("/{id}/lock")
    public UserResponse lock(@PathVariable Long id) {
        return UserResponse.from(service.lockAccount(id));
    }

    /** POST /api/users/{id}/unlock → 200; đang mở thì giữ nguyên */
    @PostMapping("/{id}/unlock")
    public UserResponse unlock(@PathVariable Long id) {
        return UserResponse.from(service.unlockAccount(id));
    }
}
