package com.shoplab.user.internal;

import com.shoplab.common.DbConstraints;
import com.shoplab.common.StaleVersionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final TransactionTemplate checkTx;    // chỉ đọc: kiểm tra trùng trước khi băm
    private final TransactionTemplate insertTx;   // ghi user + account

    public UserService(UserRepository repo, PasswordEncoder passwordEncoder, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
        this.checkTx = new TransactionTemplate(txManager);
        this.checkTx.setReadOnly(true);
        this.checkTx.setName("UserService.register: check duplicates");   // tên hiện trong log BEGIN / COMMIT
        this.insertTx = new TransactionTemplate(txManager);
        this.insertTx.setName("UserService.register: insert");
    }

    // ---------- ĐĂNG KÝ: tạo User + Account ----------
    /**
     * Tạo User + Account. BCrypt cố ý chậm (~80ms), nên băm NGOÀI transaction để không giữ connection DB
     * trong lúc băm (pool chỉ có 10 connection, đăng ký dồn dập sẽ làm mọi API khác phải chờ connection):
     * <ol>
     *   <li>transaction ngắn, chỉ đọc: kiểm tra trùng email / username. Trùng thì khỏi băm;</li>
     *   <li>băm mật khẩu: không có transaction, không giữ connection;</li>
     *   <li>transaction ngắn: INSERT. Request khác đăng ký cùng email / username chen vào giữa bước 1 và 3
     *       thì unique constraint chặn, và saveAndFlush vẫn trả lỗi trùng (409).</li>
     * </ol>
     * SUPPORTS đè @Transactional(readOnly = true) của class: bản thân method không mở transaction. Bên gọi đã có
     * transaction (vd test) thì bước 1 và 3 chạy chung transaction đó.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public User register(RegisterUserCommand command) {
        checkPasswordLength(command.password());
        String email = User.normalizeEmail(command.email());
        String username = Account.normalizeUsername(command.username());

        checkTx.executeWithoutResult(status -> {
            if (repo.existsByEmail(email)) {
                throw new DuplicateEmailException(email);
            }
            if (repo.existsByAccountUsername(username)) {
                throw new DuplicateUsernameException(username);
            }
        });

        String passwordHash = passwordEncoder.encode(command.password());

        return insertTx.execute(status -> saveAndFlush(new User(command, passwordHash)));
    }

    // ---------- HỒ SƠ ----------
    public User getById(Long id) {
        return findOrThrow(id);
    }

    /**
     * Sửa hồ sơ, chỉ khi client đang sửa đúng version hiện tại (optimistic locking qua @Version).
     * Hai lớp chặn, cùng trả 409 concurrent-modification:
     *  - client gửi version cũ (đã có người sửa sau lần client đọc) → StaleVersionException;
     *  - có người sửa xen vào giữa lúc kiểm tra và lúc ghi → UPDATE ... WHERE version = ? không khớp dòng nào,
     *    Hibernate báo ObjectOptimisticLockingFailureException.
     */
    @Transactional
    public User updateProfile(Long id, UpdateProfileCommand changes) {
        User user = findOrThrow(id);
        StaleVersionException.check("Hồ sơ người dùng", user, changes.expectedVersion());
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
