package com.shoplab.user.internal;

import com.shoplab.common.DbConstraints;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;

/**
 * Đăng ký, xem / sửa hồ sơ, khoá / mở khoá tài khoản.
 * Nhận command và trả entity (kèm Account đã nạp sẵn), không biết gì về HTTP hay DTO web.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    /** BCrypt chỉ dùng 72 byte đầu của mật khẩu; Spring Security từ chối băm mật khẩu dài hơn. */
    static final int MAX_PASSWORD_BYTES = 72;

    private static final String UK_USERS_EMAIL = "uk_users_email";
    private static final String UK_ACCOUNTS_USERNAME = "uk_accounts_username";

    private final UserRepository repo;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository repo, PasswordEncoder passwordEncoder) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
    }

    // ---------- ĐĂNG KÝ: tạo User + Account ----------
    @Transactional
    public User register(RegisterUserCommand command) {
        checkPasswordLength(command.password());

        String email = User.normalizeEmail(command.email());
        if (repo.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        String username = Account.normalizeUsername(command.username());
        if (repo.existsByAccountUsername(username)) {
            throw new DuplicateUsernameException(username);
        }

        // Băm sau cùng: BCrypt cố ý chậm, không tốn CPU cho request đằng nào cũng bị từ chối
        User user = new User(command, passwordEncoder.encode(command.password()));
        return saveAndFlush(user);
    }

    // ---------- HỒ SƠ ----------
    public User getById(Long id) {
        return findOrThrow(id);
    }

    /**
     * Sửa hồ sơ, chỉ khi client đang sửa đúng version hiện tại (optimistic locking qua @Version).
     * Hai lớp chặn, cùng trả 409 concurrent-modification:
     *  - client gửi version cũ (đã có người sửa sau lần client đọc) → ProfileVersionConflictException;
     *  - có người sửa xen vào giữa lúc kiểm tra và lúc ghi → UPDATE ... WHERE version = ? không khớp dòng nào,
     *    Hibernate báo ObjectOptimisticLockingFailureException.
     */
    @Transactional
    public User updateProfile(Long id, UpdateProfileCommand changes) {
        User user = findOrThrow(id);
        if (user.getVersion() != changes.expectedVersion()) {
            throw new ProfileVersionConflictException(id, changes.expectedVersion(), user.getVersion());
        }
        if (changes.fullName() != null) user.changeFullName(changes.fullName());
        if (changes.phone() != null)    user.changePhone(changes.phone());

        // flush ngay để version và updatedAt trả về là giá trị mới
        return saveAndFlush(user);
    }

    // ---------- KHOÁ / MỞ KHOÁ TÀI KHOẢN ----------
    @Transactional
    public User lockAccount(Long id) {
        User user = findOrThrow(id);
        user.getAccount().lock();
        return saveAndFlush(user);
    }

    @Transactional
    public User unlockAccount(Long id) {
        User user = findOrThrow(id);
        user.getAccount().unlock();
        return saveAndFlush(user);
    }

    private User findOrThrow(Long id) {
        return repo.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    /** Mật khẩu dài quá giới hạn của BCrypt → lỗi 400 thay vì IllegalArgumentException (500) khi băm. */
    private static void checkPasswordLength(String password) {
        Assert.notNull(password, "password không được null");
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new InvalidPasswordException("tối đa " + MAX_PASSWORD_BYTES
                    + " byte khi mã hoá UTF-8 (chữ có dấu chiếm 2–3 byte)");
        }
    }

    /**
     * Lưu và flush ngay để lỗi DB bay ra tại đây.
     * Hai request đăng ký cùng email / username có thể cùng lọt qua bước kiểm tra trước;
     * khi đó DB chặn bằng unique constraint và lỗi vẫn được trả về là trùng email / username.
     */
    private User saveAndFlush(User user) {
        try {
            return repo.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            if (DbConstraints.isViolated(ex, UK_USERS_EMAIL)) {
                throw new DuplicateEmailException(user.getEmail());
            }
            if (DbConstraints.isViolated(ex, UK_ACCOUNTS_USERNAME)) {
                throw new DuplicateUsernameException(user.getAccount().getUsername());
            }
            throw ex;
        }
    }
}
