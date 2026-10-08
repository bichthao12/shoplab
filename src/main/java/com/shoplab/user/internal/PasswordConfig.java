package com.shoplab.user.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * Băm mật khẩu bằng BCrypt (chỉ dùng spring-security-crypto, chưa bật Spring Security).
 * Hash lưu kèm tiền tố thuật toán, vd {bcrypt}$2a$10$...: sau này đổi thuật toán (argon2...) chỉ cần thêm
 * encoder mới làm mặc định, hash cũ vẫn kiểm tra được và có thể băm lại dần khi người dùng đăng nhập.
 */
@Configuration(proxyBeanMethods = false)
class PasswordConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder()));
    }
}
