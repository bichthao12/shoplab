package com.shoplab.user.internal;

/** Trạng thái tài khoản, lưu dạng chuỗi (khớp CHECK ck_accounts_status). */
public enum AccountStatus {
    /** Dùng bình thường. */
    ACTIVE,
    /** Tạm khoá, mở lại được. */
    LOCKED,
    /** Vô hiệu hoá hẳn: không khoá / mở qua API được nữa. */
    DISABLED
}
