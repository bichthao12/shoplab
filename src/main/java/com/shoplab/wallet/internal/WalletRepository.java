package com.shoplab.wallet.internal;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Chỉ dùng trong package wallet. */
interface WalletRepository extends JpaRepository<Wallet, Long> {

    /** SELECT ... FOR UPDATE một ví: khoá dòng tới hết transaction, giao dịch khác muốn khoá phải chờ. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
