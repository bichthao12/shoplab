package com.shoplab.user.internal;

import org.springframework.data.jpa.repository.JpaRepository;

/** Chỉ dùng trong package user. Account được lưu / nạp cùng User nên không có repository riêng. */
interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmail(String email);

    boolean existsByAccountUsername(String username);
}
