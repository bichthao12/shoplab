package com.shoplab.user;

/**
 * API của module user cho module khác: tra người dùng đang được phép giao dịch (vd đặt hàng).
 */
public interface UserDirectory {

    /**
     * Người dùng có tài khoản đang ACTIVE, kèm tên và email hiện tại trong hồ sơ.
     *
     * @throws UserUnavailableException người dùng không tồn tại, hoặc tài khoản đang bị khoá / vô hiệu hoá
     */
    UserSummary requireActiveUser(long userId);
}
