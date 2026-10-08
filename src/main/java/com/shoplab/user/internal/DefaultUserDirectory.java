package com.shoplab.user.internal;

import com.shoplab.user.UserDirectory;
import com.shoplab.user.UserSummary;
import com.shoplab.user.UserUnavailableException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cài đặt API UserDirectory: đọc User kèm Account (một câu JOIN) và kiểm tra trạng thái tài khoản. */
@Service
class DefaultUserDirectory implements UserDirectory {

    private final UserRepository repo;

    DefaultUserDirectory(UserRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(readOnly = true)
    public UserSummary requireActiveUser(long userId) {
        User user = repo.findById(userId).orElseThrow(() ->
                new UserUnavailableException("Người dùng id = " + userId + " không tồn tại"));

        // switch không có default: thêm trạng thái mới vào AccountStatus mà quên xử lý ở đây thì không biên dịch được
        return switch (user.getAccount().getStatus()) {
            case ACTIVE -> new UserSummary(user.getId(), user.getFullName(), user.getEmail());
            case LOCKED -> throw new UserUnavailableException(
                    "Tài khoản của người dùng id = " + userId + " đang bị khoá");
            case DISABLED -> throw new UserUnavailableException(
                    "Tài khoản của người dùng id = " + userId + " đã bị vô hiệu hoá");
        };
    }
}
