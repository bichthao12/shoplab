package com.shoplab.wallet.internal;

import com.shoplab.common.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.springframework.util.Assert;

import java.math.BigDecimal;

/** Ví tiền của một người dùng. Mọi thay đổi số dư đi qua deposit / withdraw; số dư không bao giờ âm. */
@Entity
@Table(name = "wallets")
@SequenceGenerator(sequenceName = "wallets_id_seq", allocationSize = 50)
public class Wallet extends AuditedEntity {

    /** Id bên module user, không có khoá ngoại. */
    @Column(nullable = false, unique = true, updatable = false)
    private Long userId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO.setScale(2);

    protected Wallet() {
        // dành cho JPA
    }

    /** Ví mới, số dư 0. Chỉ tạo được trong package wallet (qua WalletService). */
    Wallet(long userId) {
        this.userId = userId;
    }

    public Long getUserId() { return userId; }
    public BigDecimal getBalance() { return balance; }

    // ---------- Thay đổi số dư: chỉ gọi được trong package wallet ----------

    void deposit(BigDecimal amount) {
        requirePositive(amount);
        balance = balance.add(amount);
    }

    /** Không đủ tiền → InsufficientBalanceException, số dư giữ nguyên. */
    void withdraw(BigDecimal amount) {
        requirePositive(amount);
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(getId(), amount, balance);
        }
        balance = balance.subtract(amount);
    }

    private static void requirePositive(BigDecimal amount) {
        Assert.isTrue(amount != null && amount.signum() > 0, () -> "amount phải > 0, nhận được " + amount);
    }
}
