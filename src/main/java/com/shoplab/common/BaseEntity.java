package com.shoplab.common;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * Khoá chính chung cho mọi entity.
 *
 * Id lấy từ sequence riêng của từng bảng: entity con đặt @SequenceGenerator(sequenceName = ..., allocationSize = 50)
 * trên class của mình (JPA 3.2: generator không đặt tên sẽ áp dụng cho id của entity đó).
 * Hibernate xin trước 50 id mỗi lần nên không phải INSERT ngay từng dòng để biết id,
 * và gộp được nhiều câu INSERT thành một lượt gửi (batch) – điều mà IDENTITY không làm được.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    public Long getId() { return id; }
}
