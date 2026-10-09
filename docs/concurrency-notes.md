# Ghi chú đồng thời: mức cô lập, postmortem, câu hỏi

Tổng kết những gì đã dựng và "phá" trong ShopLab. Mọi con số ở đây đều đo thật, bằng test hoặc script trong repo.

- [1. Bảng tự lập: mức cô lập × hiện tượng](#1-bảng-tự-lập-mức-cô-lập--hiện-tượng)
- [2. 5 ý tóm tắt từ phần đọc](#2-5-ý-tóm-tắt-từ-phần-đọc)
- [3. Postmortem cho 12 kịch bản đã "phá"](#3-postmortem-cho-12-kịch-bản-đã-phá)
- [4. Ba câu hỏi](#4-ba-câu-hỏi)

---

## 1. Bảng tự lập: mức cô lập × hiện tượng

Lập bằng thí nghiệm `IsolationLevelTests`, không chép từ tài liệu: hai connection T1, T2 chạy từng bước theo thứ tự cố định, ở cả 3 mức cô lập. Không bước nào phải chờ khoá, nên kết quả không phụ thuộc thời điểm.

| Mức cô lập | Lost update | Write skew | Phantom |
|---|---|---|---|
| **Read Committed** (mặc định) | **Xảy ra**: số dư 120 thay vì 130 | **Xảy ra**: không còn bác sĩ nào trực | **Xảy ra**: đếm lần 1 ra 2, lần 2 ra 3 |
| **Repeatable Read** | Chặn: lỗi `40001`, phải chạy lại | **Xảy ra** | Chặn, không lỗi (snapshot) |
| **Serializable** | Chặn: lỗi `40001`, phải chạy lại | Chặn: lỗi `40001`, phải chạy lại | Chặn, không lỗi (snapshot) |

Kịch bản của từng hiện tượng:

| Hiện tượng | Thí nghiệm | Sai ở đâu |
|---|---|---|
| **Lost update** | T1, T2 cùng đọc số dư 100. T1 ghi `100 + 10`, commit. T2 ghi `100 + 20`, commit | Hai lần ghi dựa trên cùng một lần đọc: lần sau đè lần trước, mất `+10` |
| **Write skew** | Quy tắc "luôn còn ít nhất 1 người trực". T1, T2 cùng thấy 2 người trực. T1 cho `alice` nghỉ, T2 cho `bob` nghỉ (hai dòng **khác nhau**), cùng commit | Mỗi transaction riêng lẻ đều hợp lệ, cộng lại thì vi phạm quy tắc. Không có dòng nào bị hai bên cùng ghi, nên khoá dòng không thấy xung đột |
| **Phantom** | T1 đếm `category = 'x'` (2). T2 thêm một dòng `'x'`, commit. T1 đếm lại | Cùng một câu truy vấn trong một transaction trả hai kết quả khác nhau |

Lỗi thật mà PostgreSQL trả về:

```
LOST_UPDATE @ REPEATABLE_READ  → 40001: could not serialize access due to concurrent update
LOST_UPDATE @ SERIALIZABLE     → 40001: could not serialize access due to concurrent update
WRITE_SKEW  @ SERIALIZABLE     → 40001: could not serialize access due to read/write dependencies among transactions
```

Đọc bảng:

- **"Chặn" ở Repeatable Read / Serializable thường là huỷ một transaction bằng lỗi `40001`**, không phải tự sửa cho đúng. Ứng dụng phải bắt `40001` và chạy lại **cả** transaction. Thiếu bước này thì người dùng nhận lỗi.
- **Repeatable Read của PostgreSQL chặn được phantom.** Chuẩn SQL cho phép phantom ở mức này, nhưng PostgreSQL dùng một snapshot cho cả transaction nên lần đếm thứ hai không thấy dòng mới.
- **Write skew chỉ Serializable chặn được.** Repeatable Read không thấy xung đột vì hai bên ghi hai dòng khác nhau.
- **Không mức nào có dirty read** trong PostgreSQL. `READ UNCOMMITTED` chạy như `READ COMMITTED`.

**ShopLab chạy ở Read Committed** (mặc định) và chặn từng hiện tượng bằng cách khác, không cần vòng lặp chạy lại:

| Hiện tượng trong ShopLab | Cách chặn | Ở đâu |
|---|---|---|
| Lost update tồn kho (bán vượt) | Một câu `UPDATE ... SET stock = stock - :q WHERE stock >= :q`, nhận khi cập nhật đúng 1 dòng | `DefaultProductInventory` |
| Lost update tồn kho (nhập hàng) | `stock = stock + :delta` trong một câu lệnh | `ProductService.adjustStock` |
| Lost update số dư ví | `SELECT ... FOR UPDATE`, khoá theo thứ tự id | `WalletService.transfer` |
| Lost update giữa các request (form đã cũ) | `@Version` + client gửi `version` đã đọc | `UserService.updateProfile`, `ProductService.update` |
| Trùng email / SKU / ví (giống write skew: "chưa có thì thêm") | Unique constraint của DB | migration `V1`, `V5`, `V12` |

Chạy lại: `./mvnw test -Dtest=IsolationLevelTests`. Thí nghiệm đã chạy trên PostgreSQL 16 (máy dev) và cho cùng kết quả mà test khẳng định; bản app dùng PostgreSQL 17 (Docker / Testcontainers), cách xử lý mức cô lập của hai bản này giống nhau.

---

## 2. 5 ý tóm tắt từ phần đọc

Từ chương *Concurrency Control* của tài liệu PostgreSQL (MVCC, Transaction Isolation, Explicit Locking). Mỗi ý đều đã kiểm chứng bằng test trong repo.

1. **MVCC: đọc không chặn ghi, ghi không chặn đọc.** Mỗi câu lệnh đọc một snapshot dữ liệu đã commit, nên `SELECT` thường vẫn đọc được dòng đang bị transaction khác `FOR UPDATE` hay `UPDATE` (thí nghiệm `FOR UPDATE` trong `IsolationLevelTests`). Đổi lại, dữ liệu vừa đọc có thể đã cũ ngay sau đó.
2. **Read Committed (mặc định): mỗi câu lệnh một snapshot.** Câu `UPDATE` / `DELETE` / `SELECT ... FOR UPDATE` gặp dòng đang bị transaction khác sửa thì **chờ**, rồi kiểm tra lại điều kiện `WHERE` trên phiên bản mới nhất. Vì vậy `UPDATE ... WHERE stock >= :q` an toàn. Ngược lại, đọc ở câu này rồi tính và ghi ở câu sau thì mất cập nhật, dù nằm trong cùng transaction.
3. **Repeatable Read: một snapshot cho cả transaction**, lấy ở câu lệnh đầu tiên. Không có phantom. Ghi vào dòng mà transaction khác đã sửa và commit sau snapshot đó thì bị huỷ với `40001`, ứng dụng phải chạy lại. Write skew vẫn lọt.
4. **Serializable (SSI)**: như Repeatable Read, cộng thêm theo dõi phụ thuộc đọc / ghi giữa các transaction. Khi kết quả không khớp với bất kỳ thứ tự chạy tuần tự nào, PostgreSQL huỷ một transaction (`40001`), nên chặn được write skew. Mức này không thêm khoá chờ nào, nhưng sinh nhiều `40001` hơn, nên mọi transaction đều cần cơ chế chạy lại.
5. **Khoá tường minh và deadlock.** `SELECT ... FOR UPDATE` chỉ khoá các dòng nó trả về. Ở mức bảng, nó giữ `ROW SHARE`, chỉ xung đột với `EXCLUSIVE` / `ACCESS EXCLUSIVE` (DDL, `TRUNCATE`, `LOCK TABLE`). PostgreSQL không có gap lock nên khoá này không chặn `INSERT`. Hai transaction chờ khoá của nhau thì sau `deadlock_timeout` (mặc định 1 giây) PostgreSQL huỷ một bên với `40P01`. Cách tránh là khoá theo cùng một thứ tự, và đặt `lock_timeout` để không chờ vô hạn.

---

## 3. Postmortem cho 12 kịch bản đã "phá"

Mỗi kịch bản có bản lỗi trong `scripts/bugs/` và test bảo vệ. `scripts/red-green.ps1` chạy lại: bản sửa xanh, bản lỗi đỏ, 12/12. Số liệu dưới đây đo thật trên bản lỗi.

### 1. Bán vượt khi 1.000 người mua món cuối (`oversell`)
- **Hiện tượng:** 1.000 lượt mua sản phẩm còn 1 cái tạo ra **10 đơn**. Kho vẫn báo 0, nên nhìn tồn kho không thấy gì sai.
- **Nguyên nhân:** đọc kho → kiểm tra → trừ ở Java → ghi con số đã tính. Giữa lúc đọc và lúc ghi, 10 transaction (bằng số connection của pool) cùng thấy "còn 1".
- **Cách sửa:** một câu `UPDATE ... SET stock = stock - :q WHERE ... AND stock >= :q`, chỉ nhận khi cập nhật đúng 1 dòng; `CHECK (stock >= 0)` là lớp chặn cuối.
- **Phát hiện sớm hơn:** test bắn 1.000 request cùng lúc rồi đếm **số đơn**, không chỉ nhìn tồn kho; đối soát định kỳ "số đã bán ≤ số đã nhập".
- **Bài học:** "kiểm tra rồi làm" trên dữ liệu dùng chung phải là một bước nguyên tử trong DB. Tồn kho trông đúng không có nghĩa là không bán vượt.

### 2. Deadlock khi hai người chuyển tiền cho nhau (`deadlock`)
- **Hiện tượng:** A→B và B→A cùng lúc: một lượt bị huỷ sau khoảng 1 giây (`40P01`). 100 cặp qua API: log có 87 lần deadlock, request xếp hàng chờ quá 30 giây.
- **Nguyên nhân:** khoá hai ví theo thứ tự tham số (ví nguồn trước). Mỗi bên giữ một khoá rồi chờ khoá bên kia đang giữ.
- **Cách sửa:** khoá theo thứ tự id tăng dần ở mọi chỗ khoá nhiều ví; deadlock còn lọt thì trả `409 deadlock` + `Retry-After`.
- **Phát hiện sớm hơn:** test chuyển ngược chiều cùng lúc, có chèn chờ 50ms giữa hai lần khoá để luôn chồng nhau; theo dõi số lần `40P01` trong log / `pg_stat_database.deadlocks`.
- **Bài học:** chỗ nào khoá nhiều dòng thì mọi nơi phải khoá theo cùng một thứ tự. Deadlock không làm sai dữ liệu nhưng làm chậm và làm thất bại những lượt hợp lệ.

### 3. Log deadlock không có mã lỗi (`deadlock-log`)
- **Hiện tượng:** dòng log của app chỉ ghi "Deadlock detected: ERROR: deadlock detected". Với truy vấn chạy qua `JdbcClient`, không dòng log nào có `40P01`.
- **Nguyên nhân:** chỉ ghi message của PostgreSQL và trông vào Hibernate in kèm mã lỗi; Hibernate chỉ làm vậy khi lỗi đi qua nó.
- **Cách sửa:** `GlobalExceptionHandler` tự ghi `Deadlock detected (SQLState 40P01): ...`.
- **Phát hiện sớm hơn:** test bắt output log (`OutputCaptureExtension`); đặt cảnh báo theo mã lỗi chứ không theo câu chữ.
- **Bài học:** log phải có mã máy đọc được, không phụ thuộc thư viện nào tình cờ in ra.

### 4. Chờ khoá vô hạn (`lock-timeout`)
- **Hiện tượng:** request gặp dòng đang bị khoá thì chờ mãi tới khi client hết giờ (30 giây), giữ một connection suốt lúc chờ.
- **Nguyên nhân:** `lock_timeout` mặc định của PostgreSQL là 0, tức là chờ vô hạn.
- **Cách sửa:** `SET lock_timeout = '5s'` cho mọi connection (Hikari `connection-init-sql`) → `409` + `Retry-After`. Giá trị phải dài hơn `deadlock_timeout` (1 giây) để deadlock vẫn được báo đúng loại.
- **Phát hiện sớm hơn:** test cho một connection khác giữ khoá rồi đo thời gian chờ; theo dõi `pg_stat_activity` với `wait_event_type = 'Lock'`.
- **Bài học:** chỗ nào chờ cũng cần giới hạn. Trả lỗi nhanh để client thử lại tốt hơn treo cả pool.

### 5. Ghi đè hồ sơ bằng form đã cũ (`profile-lost-update`)
- **Hiện tượng:** Bình lưu form mở từ trước → `200`, ghi đè số điện thoại An vừa đổi, không ai biết.
- **Nguyên nhân:** `@Version` chỉ so với version mà **transaction** vừa đọc. Request của Bình đọc lại bản mới nhất rồi mới sửa, nên câu `UPDATE ... WHERE version = ?` vẫn khớp.
- **Cách sửa:** bắt buộc client gửi `version` đã đọc. Khác version hiện tại thì trả `409` kèm `currentVersion`.
- **Phát hiện sớm hơn:** test hai bước tuần tự, không cần chạy đồng thời. Khi review, API sửa dữ liệu mà không nhận `version` (hay `ETag` / `If-Match`) là dấu hiệu cần xem.
- **Bài học:** lost update xảy ra cả giữa các request (trong lúc người dùng sửa form), không chỉ giữa các transaction.

### 6. Xung đột `@Version` thành 500 (`version-409`)
- **Hiện tượng:** hồ sơ bị sửa chen vào giữa lúc so version và lúc ghi → `500 Internal Server Error`.
- **Nguyên nhân:** không có handler cho `ObjectOptimisticLockingFailureException`, nên lỗi rơi vào handler bắt mọi exception.
- **Cách sửa:** map sang `409 concurrent-modification` dạng Problem Details.
- **Phát hiện sớm hơn:** test ép đúng nhánh `@Version` (một connection khác sửa dòng đó trong lúc request đang chờ ở câu `UPDATE`); cảnh báo khi tỉ lệ 500 tăng.
- **Bài học:** lỗi do đồng thời là lỗi dự kiến. Nó cần mã trả về riêng để client biết tải lại rồi thử lại.

### 7. Ghi đè sản phẩm / tồn kho bằng số đã cũ (`product-lost-update`)
- **Hiện tượng:** `PATCH` sản phẩm với version cũ → `200`, ghi đè thay đổi của người khác. Trước khi tách tồn kho: admin đặt `stock = 15` theo con số đọc trước khi có đơn, làm mất 3 cái đã bán.
- **Nguyên nhân:** không so `version` client gửi, và `stock` từng là con số tuyệt đối trong `PATCH`.
- **Cách sửa:** bắt buộc `version`. Tồn kho tách ra API cộng / trừ riêng, đơn hàng không còn đổi `version`, cột `stock` là `updatable = false`.
- **Phát hiện sớm hơn:** test version cũ chạy tuần tự; test admin sửa sau khi có đơn.
- **Bài học:** một `version` cho cả dòng làm phát sinh xung đột vô cớ (sửa giá bị 409 vì có người mua). Dữ liệu thay đổi độc lập thì nên tách ra.

### 8. Mất lượt nhập hàng khi chạy cùng đơn hàng (`stock-adjustment`)
- **Hiện tượng:** 20 lượt nhập +1 và 10 đơn chạy cùng lúc, kho ban đầu 10: kho còn **10** thay vì 20 (với 50 lượt nhập: 11 thay vì 50). Không request nào báo lỗi.
- **Nguyên nhân:** đọc tồn kho → cộng ở Java → ghi con số tuyệt đối. Các lượt chạy cùng lúc ghi đè nhau.
- **Cách sửa:** `UPDATE ... SET stock = stock + :delta WHERE stock + :delta >= 0` trong một câu lệnh, bắt buộc `Idempotency-Key` (gửi lại `+5` không được thành `+10`).
- **Phát hiện sớm hơn:** test trộn nhập hàng và đặt hàng cùng lúc rồi so tổng; đối soát "kho = nhập − bán".
- **Bài học:** gửi phần thay đổi (delta) thay cho con số tuyệt đối. Nhưng delta không tự an toàn khi gửi lại, nên phải đi kèm idempotency.

### 9. Đăng ký trùng email cùng lúc (`duplicate-email`)
- **Hiện tượng:** 20 đăng ký cùng email gửi cùng lúc → **20** người dùng (50 request → 50 người dùng).
- **Nguyên nhân:** chỉ `existsByEmail` rồi `INSERT`, không có unique constraint. Các request cùng kiểm tra trước khi request đầu tiên kịp ghi.
- **Cách sửa:** unique constraint `uk_users_email`, chuẩn hoá chữ thường + `CHECK (email = lower(email))`, dịch lỗi vi phạm về `409 duplicate-email`.
- **Phát hiện sớm hơn:** test 20 request cùng lúc; truy vấn định kỳ tìm email trùng.
- **Bài học:** quy tắc "duy nhất" phải nằm trong DB. Kiểm tra trước chỉ để trả lỗi sớm. Số lượng tạo trùng còn phụ thuộc chi tiết khác (trước đây pool 10 connection vô tình giới hạn ở 10), nên đừng tin vào con số đo được để coi là an toàn.

### 10. Vi phạm unique thành 500 (`unique-500`)
- **Hiện tượng:** tạo ví thứ hai cho cùng người dùng chen ngang → `500`.
- **Nguyên nhân:** service không dịch lỗi của `uk_wallets_user`, và không có handler dự phòng cho `DataIntegrityViolationException`.
- **Cách sửa:** service dịch lỗi theo tên constraint (`DbConstraints.isViolated`) → `409 duplicate-wallet`; handler dự phòng trả `409 data-integrity` cho lỗi chưa được dịch.
- **Phát hiện sớm hơn:** liệt kê mọi constraint trong DB, mỗi constraint một test cho trường hợp DB chặn (lọt qua bước kiểm tra trước).
- **Bài học:** vi phạm constraint là đường đi bình thường khi có đồng thời, không phải lỗi hệ thống.

### 11. Gọi nội bộ làm mất transaction (`self-invocation`)
- **Hiện tượng:** chuyển tiền hàng loạt: lượt lỗi vẫn trừ 30 của A mà không ai nhận → tổng còn 170 thay vì 200.
- **Nguyên nhân:** `transferAll` gọi `this.transfer(...)`, không đi qua proxy nên `@Transactional` bị bỏ qua. Mỗi lời gọi `save` tự mở transaction và commit ngay.
- **Cách sửa:** tách sang bean khác (nên dùng), gọi qua proxy (`self.transfer`), hoặc `TransactionTemplate`.
- **Phát hiện sớm hơn:** test kiểm tra `TransactionSynchronizationManager.isActualTransactionActive()` bên trong method; trong log `tx`, thấy mỗi lời gọi repository có một cặp `BEGIN` / `COMMIT` riêng là dấu hiệu; quét code tìm method `@Transactional` bị gọi nội bộ.
- **Bài học:** annotation chạy nhờ proxy (`@Transactional`, `@Async`, `@Cacheable`) chỉ có tác dụng khi được gọi từ bên ngoài bean.

### 12. Băm mật khẩu trong transaction (`hash-in-transaction`)
- **Hiện tượng:** mỗi lần đăng ký giữ một connection khoảng **88 ms**, trong đó 77 ms là băm BCrypt. Đăng ký dồn dập sẽ chiếm hết pool 10 connection.
- **Nguyên nhân:** cả `register` là một transaction, BCrypt (cố ý chậm) chạy bên trong.
- **Cách sửa:** kiểm tra trùng (transaction ngắn) → băm (ngoài transaction) → `INSERT` (transaction ngắn). Thời gian giữ connection giảm còn khoảng **13 ms**.
- **Phát hiện sớm hơn:** đo `BEGIN` → `COMMIT` trong log `tx`; theo dõi số connection Hikari đang dùng / đang chờ; test spy kiểm tra lúc băm không có transaction và không giữ connection.
- **Bài học:** transaction chỉ bao phần cần DB. Việc chậm (băm, gọi API ngoài, chờ) phải để ra ngoài.

---

## 4. Ba câu hỏi

### 1. Bọc trong transaction có chống được lost update không?

**Không, ở mức mặc định (Read Committed).** Transaction chỉ đảm bảo các thay đổi của nó cùng thành công hoặc cùng huỷ. Nó không ngăn hai transaction cùng đọc một giá trị rồi ghi đè lên nhau.

- Bằng chứng: bản ngây thơ trong `NaiveStockDeductionTests` có **mỗi lượt mua nằm trong một transaction riêng**, vậy mà 8 người mua được khi kho chỉ có 5, kho còn 4. Ô "Lost update × Read Committed" của bảng trên cho số dư 120 thay vì 130.
- Ở Repeatable Read / Serializable, PostgreSQL phát hiện và huỷ một bên (`40001`), nên ứng dụng phải có cơ chế chạy lại.
- Cách ShopLab chống, ngay ở Read Committed:
  - ghi bằng một câu lệnh nguyên tử (`stock = stock - :q WHERE stock >= :q`);
  - khoá dòng trước khi đọc (`SELECT ... FOR UPDATE`);
  - optimistic lock (`@Version` + client gửi `version`).

### 2. `SELECT … FOR UPDATE` có khoá cả bảng không?

**Không.** Nó chỉ khoá các dòng câu truy vấn trả về. Ở mức bảng, nó giữ `RowShareLock`, khoá này chỉ chặn DDL và `LOCK TABLE ... EXCLUSIVE`.

Thí nghiệm (`IsolationLevelTests.selectForUpdate_locksReturnedRowsNotTable`): T1 chạy `SELECT ... WHERE id = 1 FOR UPDATE`, rồi T2 lần lượt thử:

| T2 làm gì | Kết quả |
|---|---|
| Đọc dòng 1 (`SELECT` thường) | Chạy được (MVCC) |
| Sửa dòng 2 | Chạy được |
| Thêm dòng 3 | Chạy được (PostgreSQL không có gap lock) |
| Sửa dòng 1 | **Bị chặn** (chờ tới `lock_timeout`, lỗi `55P03`) |
| `LOCK TABLE ... IN EXCLUSIVE MODE` | **Bị chặn** (`55P03`) |

Lưu ý:
- Câu `FOR UPDATE` không có `WHERE` thì khoá **mọi dòng nó trả về**. Như vậy gần giống khoá toàn bộ dữ liệu hiện có, nhưng vẫn không chặn được dòng mới thêm vào.
- Vì không chặn `INSERT`, `FOR UPDATE` không dùng để chống trùng kiểu "chưa có thì thêm" được; việc đó cần unique constraint.

### 3. Hai người chuyển tiền cho nhau cùng lúc bị deadlock. Sửa thế nào?

**Khoá hai ví theo một thứ tự cố định, không phụ thuộc chiều chuyển.** ShopLab khoá ví có id nhỏ trước, id lớn sau (`WalletService.transfer`).

- A→B và B→A đều xin khoá ví có id nhỏ hơn trước. Lượt đến sau phải chờ ngay ở khoá đầu tiên, khi chưa giữ khoá nào, nên không thể có cảnh mỗi bên giữ một khoá rồi chờ nhau.
- Bằng chứng: cùng điều kiện (chờ 50ms giữa hai lần khoá), khoá theo thứ tự tham số thì deadlock ở mọi lần chạy, còn khoá theo id thì cả hai lượt đều chuyển xong. 100 cặp ngược chiều qua API: không có deadlock nào, tổng số dư giữ nguyên. Khoá theo thứ tự tham số thì log có 87 lần `40P01`.
- Ngoài ra:
  - giữ transaction ngắn;
  - đặt `lock_timeout` để không chờ vô hạn;
  - coi `40P01` là lỗi có thể thử lại (`409 deadlock` + `Retry-After`), vì PostgreSQL vẫn phát hiện deadlock và huỷ một bên.
- Không nên:
  - tăng `deadlock_timeout`, vì chỉ làm chờ lâu hơn;
  - khoá cả bảng, vì như vậy mọi chuyển tiền phải chạy lần lượt.
