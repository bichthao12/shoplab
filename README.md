# ShopLab

REST API bán hàng tối giản dùng để luyện thiết kế API: CRUD sản phẩm, đặt hàng có **Idempotency-Key**, đăng ký người dùng (mật khẩu băm BCrypt), trả mọi lỗi theo chuẩn **ProblemDetail (RFC 9457)**.

**Công nghệ:** Java 21 · Spring Boot 4 (Web MVC, Data JPA, Validation) · Spring Modulith · Spring Security Crypto (chỉ BCrypt) · PostgreSQL 17 · Flyway · Testcontainers

---

## 1. Cách chạy

### Yêu cầu
- JDK 21
- Docker Desktop (đang chạy – cần cho cả DB lẫn test)

### Chạy app với PostgreSQL trong Docker Compose

```bash
docker compose up -d          # khởi động PostgreSQL (cổng 5432)
./mvnw spring-boot:run        # Windows: .\mvnw spring-boot:run
```

App chạy ở `http://localhost:8080`. Flyway tự áp dụng mọi migration trong `db/migration` khi khởi động.

| Lệnh | Tác dụng |
|---|---|
| `docker compose ps` | Xem trạng thái DB (đợi `healthy`) |
| `docker compose down` | Dừng DB, **giữ** dữ liệu |
| `docker compose down -v` | Dừng DB và **xoá** dữ liệu (reset) |
| `docker exec -it shoplab-postgres psql -U shoplab -d shoplab` | Mở psql |

### Chạy app không cần Docker Compose

Chạy class `TestShoplabApplication` (trong `src/test/java`): app tự dựng PostgreSQL bằng Testcontainers, không cần cấu hình datasource.

### Chạy test

```bash
./mvnw test                                           # toàn bộ
./mvnw test -Dtest=OrderIdempotencyIntegrationTests   # riêng test idempotency
./mvnw test -Dtest=ModularityTests                    # kiểm tra cấu trúc module + sinh tài liệu module
./mvnw test -Dtest=DatabaseModularityTests            # kiểm tra ranh giới module ở tầng DB
```

Test đặt theo package của từng module, gồm 5 loại:

| Loại | Ví dụ | Dựng gì |
|---|---|---|
| Unit test | `ProductTest`, `OrderTest`, `UserTest`, `CreateOrderCommandTest`, `RequestFingerprintTest`, `ConcurrentlyTest` | Không Spring, không DB |
| Test slice | `GlobalExceptionHandlerTests` (`@WebMvcTest`) | Chỉ một tầng |
| Test riêng từng module | `ProductModuleTests`, `OrderModuleTests`, `UserModuleTests`, `WalletModuleTests` (`@ApplicationModuleTest`) | Chỉ một module (kèm `common`); API của module khác được mock |
| Integration test | `OrderIdempotencyIntegrationTests`, `OrderApiIntegrationTests`, `ProductApiIntegrationTests`, `UserApiIntegrationTests`, `SqlLoggingTests`, `NaiveStockDeductionTests`, `FlashSaleIntegrationTests`, `NaiveFlashSaleTests`, `TransferDeadlockTests`, `WalletApiIntegrationTests` | Cả app trên cổng ngẫu nhiên + PostgreSQL thật (Testcontainers), gồm cả kịch bản đồng thời và rollback |

Kịch bản đồng thời dùng `Concurrently.run(n, i -> ...)`: chạy N tác vụ, mỗi tác vụ trên một virtual thread, và một `CountDownLatch(N)` làm vạch xuất phát (mỗi luồng `countDown()` rồi `await()`), nên không tác vụ nào chạy trước khi đủ N luồng sẵn sàng. Kết quả trả theo thứ tự `i`; quá 60 giây thì báo `TimeoutException` và ngắt các tác vụ còn chạy.
| Test cấu trúc | `ModularityTests` (Spring Modulith + ArchUnit), `DatabaseModularityTests` | Đọc bytecode, kiểm tra ranh giới module và phân tầng trong module; đọc schema, kiểm tra không có khoá ngoại chéo module |

### Test thủ công

- **Postman:** import `shoplab.postman_collection.json` → *Run collection* (chạy đúng thứ tự). Biến `baseUrl` mặc định `http://localhost:8080`.
- **File `.http`** (IntelliJ / VS Code REST Client): `products.http`, `users.http`.
- **Bán chớp nhoáng với app đang chạy:** `scripts\flash-sale.ps1` (xem mục *Test bán chớp nhoáng*).
- **Deadlock chuyển tiền:** `scripts\transfer-deadlock.ps1` (xem mục *Deadlock*).
- **Bắn request đồng thời cùng key:**
  ```powershell
  powershell -ExecutionPolicy Bypass -File .\test-concurrent-orders.ps1 -Count 3
  ```

### Cấu hình đáng chú ý

| Mục | Giá trị | Lý do |
|---|---|---|
| `spring.jpa.hibernate.ddl-auto` | `validate` | Schema do Flyway quản lý, Hibernate chỉ kiểm tra |
| `spring.jpa.open-in-view` | `false` | Không mở transaction kéo dài tới tầng view |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | `UTC` | Thời gian lưu và đọc theo UTC |
| `spring.datasource.hikari.connection-init-sql` | `SET lock_timeout = '5s'` | Không request nào chờ khoá DB quá 5 giây (→ 409 + `Retry-After`), thay vì giữ connection chờ vô hạn |
| `spring.jpa.properties.hibernate.jdbc.batch_size` (+ `order_inserts`, `order_updates`) | `50` | Gộp các câu INSERT / UPDATE cùng loại thành một lượt gửi, vd mọi dòng của một đơn hàng |
| PostgreSQL `timezone` | `UTC` | Thời gian hiển thị trong psql cũng là UTC |
| `logging.level.sql` | `DEBUG` | Log từng câu SQL, của cả Hibernate (`org.hibernate.SQL`) lẫn `JdbcClient` (`org.springframework.jdbc.core`) |
| `logging.level.tx` | `DEBUG` | Log `BEGIN` / `COMMIT` / `ROLLBACK` của mỗi transaction mới (`common.config.TransactionLogging`) |

#### Đọc log SQL

Mỗi dòng có thời điểm (ms) và tên luồng, nên lọc theo luồng là thấy trọn một request:

```
04:12:01.293 [exec-1] TransactionLogging : BEGIN    DefaultIdempotencyService.execute
04:12:01.303 [exec-1] JdbcTemplate       : Executing prepared SQL statement [INSERT INTO idempotency_keys ...]
04:12:01.344 [exec-1] org.hibernate.SQL  : select ... from users u1_0 join accounts a1_0 ...
04:12:01.452 [exec-1] JdbcTemplate       : Executing prepared SQL statement [UPDATE products SET stock = stock - ? ... WHERE id = ? AND active AND stock >= ? RETURNING ...]
04:12:01.510 [exec-1] org.hibernate.SQL  : insert into orders ...
04:12:01.516 [exec-1] org.hibernate.SQL  : insert into order_items ...
04:12:01.550 [exec-1] JdbcTemplate       : Executing prepared SQL statement [UPDATE idempotency_keys SET status = ? ...]
04:12:01.558 [exec-1] TransactionLogging : COMMIT   DefaultIdempotencyService.execute
```

- `COMMIT` / `ROLLBACK` được ghi **sau** khi connection đã commit / rollback xong, nên mọi câu đứng trước nó trên cùng luồng đã nằm trong DB (khoá cũng được nhả lúc này).
- Chỉ transaction **mới** có dòng `BEGIN` / `COMMIT`. Service hay repository gọi bên trong (vd `UserDirectory`, `ProductInventory` khi đặt hàng) chạy chung transaction ngoài nên không có dòng riêng.
- Giá trị các tham số `?` không được log (có cả email, hash mật khẩu). Cần thì bỏ comment 2 dòng cuối phần log trong `application.properties`, chỉ trên máy mình.
- Tắt log: `--logging.level.sql=INFO --logging.level.tx=INFO`.

Mọi mốc thời gian (`createdAt`, `updatedAt`) lưu kiểu `TIMESTAMPTZ` / `Instant` và trả ra dạng ISO-8601 có `Z`, ví dụ `2026-10-06T07:04:00.123456Z`. Chúng do Spring Data JPA auditing điền và được cắt về micro giây, đúng độ chính xác của `TIMESTAMPTZ`.

Id lấy từ sequence riêng của từng bảng (`products_id_seq`...), mỗi lần Hibernate xin trước 50 id. Vì vậy id tăng dần nhưng không liền nhau (có khoảng trống, nhất là sau khi app khởi động lại).

---

## 2. Endpoint và mã trạng thái

Mọi request có body dùng `Content-Type: application/json`. Mọi response lỗi dùng `Content-Type: application/problem+json`.

### Sản phẩm – `/api/products`

| Method | Đường dẫn | Thành công | Lỗi có thể gặp |
|---|---|---|---|
| `POST` | `/api/products` | **201** + header `Location` | 400 dữ liệu không hợp lệ · 409 trùng SKU |
| `GET` | `/api/products/{id}` | **200** | 400 `id` sai kiểu · 404 không tồn tại |
| `GET` | `/api/products?category=AO&page=0&size=20&sort=price,desc` | **200** (có phân trang) | 400 `sort` theo trường không tồn tại |
| `PATCH` | `/api/products/{id}` | **200** (chỉ sửa trường được gửi) | 400 dữ liệu không hợp lệ · 404 · 409 trùng SKU / bị sửa đồng thời |
| `DELETE` | `/api/products/{id}` | **204** (không có body) | 404 · 409 sản phẩm đã nằm trong đơn hàng |

Ví dụ body tạo sản phẩm:

```json
{
  "sku": "AO-THUN-001",
  "name": "Áo thun basic cổ tròn",
  "description": "Cotton 100%",
  "category": "AO",
  "price": 199000,
  "stock": 50
}
```

### Đơn hàng – `/api/orders`

| Method | Đường dẫn | Thành công | Lỗi có thể gặp |
|---|---|---|---|
| `POST` | `/api/orders` (bắt buộc header `Idempotency-Key`) | **201** + `Location` | xem bảng bên dưới |
| `GET` | `/api/orders/{id}` | **200** | 404 |
| `GET` | `/api/orders?userId=1&page=0&size=20` | **200**: đơn của người dùng, mới nhất trước, có phân trang, không kèm dòng hàng | 400 thiếu `userId` · 400 `sort` theo trường không tồn tại |

Ví dụ:

```http
POST /api/orders
Content-Type: application/json
Idempotency-Key: 3f1c2a9e-7b4d-4c1e-9a55-0d2f6b8e1a77

{
  "userId": 1,
  "items": [{ "productId": 1, "quantity": 2 }]
}
```

Đơn gắn với người đặt qua `userId`. Tên và email khách (`customerName`, `customerEmail` trong response) được chụp từ hồ sơ lúc đặt, như `sku` và giá của dòng đơn: sửa hồ sơ sau đó không làm đổi đơn cũ. Đơn tạo trước khi có `userId` (trước V11) trả `userId: null`.

**Hành vi của `POST /api/orders`:**

| Tình huống | Mã | Ghi chú |
|---|---|---|
| Lần đầu với key mới | **201** | Tạo đơn, trừ kho |
| Gửi lại cùng key + cùng nội dung | **201** | Trả **nguyên văn** response lần đầu, header `Idempotent-Replayed: true`, không tạo đơn mới |
| Thiếu header `Idempotency-Key` | **400** | |
| Key sai định dạng | **400** | 8–255 ký tự `[A-Za-z0-9_-]`, khuyến nghị UUID |
| Body không hợp lệ (thiếu `userId`, `items` rỗng…) | **400** | Kèm `errors` theo từng trường |
| Cùng key nhưng nội dung khác | **422** | `idempotency-key-reused` |
| Người dùng không tồn tại / tài khoản bị khoá hoặc vô hiệu hoá | **422** | `invalid-order`; kiểm tra trước khi giữ hàng, kho không bị đụng tới |
| Sản phẩm không tồn tại / ngừng bán | **422** | `invalid-order` |
| Không đủ hàng | **409** | Kèm `sku`, `requested`, `available` |
| Request khác cùng key đang chạy quá 5 giây | **409** | Header `Retry-After: 1`, gửi lại với **cùng** key |

### Người dùng – `/api/users`

Mỗi người dùng có hồ sơ (bảng `users`) và đúng một tài khoản đăng nhập (bảng `accounts`). Chưa có đăng nhập; các API khác vẫn mở.

| Method | Đường dẫn | Thành công | Lỗi có thể gặp |
|---|---|---|---|
| `POST` | `/api/users` | **201** + header `Location` | 400 dữ liệu không hợp lệ · 409 trùng email / username |
| `GET` | `/api/users/{id}` | **200** | 404 |
| `PATCH` | `/api/users/{id}` | **200** (sửa `fullName`, `phone`; `"phone": ""` để xoá; bắt buộc gửi `version` đã đọc) | 400 (thiếu `version`) · 404 · 409 hồ sơ đã bị sửa sau lần đọc |
| `POST` | `/api/users/{id}/lock` | **200**, `account.status` = `LOCKED` (đã khoá thì giữ nguyên) | 404 · 409 tài khoản đã vô hiệu hoá |
| `POST` | `/api/users/{id}/unlock` | **200**, `account.status` = `ACTIVE` (đang mở thì giữ nguyên) | 404 · 409 tài khoản đã vô hiệu hoá |

Ví dụ:

```http
POST /api/users
Content-Type: application/json

{
  "email": "A.Nguyen@Example.com",
  "fullName": "Nguyễn Văn A",
  "phone": "0912345678",
  "username": "Alice.N",
  "password": "matkhau-bi-mat"
}
```

```json
{
  "id": 1,
  "email": "a.nguyen@example.com",
  "fullName": "Nguyễn Văn A",
  "phone": "0912345678",
  "account": { "username": "alice.n", "status": "ACTIVE" },
  "version": 0,
  "createdAt": "2026-10-08T03:00:00.123456Z",
  "updatedAt": "2026-10-08T03:00:00.123456Z"
}
```

- Email và username lưu chữ thường, nên trùng không phân biệt hoa/thường. Username chỉ gồm `A-Z a-z 0-9 . _ -`, dài 3–50.
- Mật khẩu 8–72 ký tự, lưu dạng hash `{bcrypt}$2a$10$...`. Response không bao giờ chứa mật khẩu hay hash. BCrypt chỉ dùng được 72 **byte** đầu, nên mật khẩu quá 72 byte UTF-8 (chữ có dấu chiếm 2–3 byte) cũng trả 400 `validation` với `errors.password`.
- `version`, `createdAt`, `updatedAt` là của hồ sơ; khoá / mở khoá chỉ đổi `account.status`.
- Trạng thái `DISABLED` (vô hiệu hoá hẳn) chưa đặt được qua API; tài khoản ở trạng thái này không khoá / mở được.
- Chưa đổi được email / username qua API (đổi email cần bước xác minh).

#### Sửa hồ sơ: bắt buộc gửi `version`

`PATCH /api/users/{id}` phải kèm `version` mà client đã đọc (từ `GET` hoặc response trước đó):

```http
PATCH /api/users/1
Content-Type: application/json

{ "fullName": "Nguyễn Văn An", "version": 0 }
```

Nếu không có `version`, hai người cùng mở form sửa hồ sơ sẽ ghi đè lên nhau mà không ai biết (lost update):

```
An   GET  → version 0, phone 0900000000
Bình GET  → version 0
An   PATCH phone = 0911111111             → 200, version 1
Bình PATCH fullName = ... (form cũ của Bình vẫn hiện phone 0900000000)
     → nếu không kiểm tra version: 200, Bình ghi đè mà không biết An vừa sửa
```

`@Version` của entity không chặn được trường hợp này. Nó chỉ thêm `AND version = ?` vào câu UPDATE với version mà
**transaction đang chạy** vừa đọc. Request của Bình đọc lại hồ sơ (version 1) rồi mới sửa, nên câu UPDATE vẫn khớp.
Vì vậy server so `version` client gửi với version hiện tại trước khi sửa. Có hai lớp kiểm tra:

1. **Client đọc từ trước** (`version` gửi lên ≠ version hiện tại) → **409** `concurrent-modification`, không sửa gì.
   Response có `expectedVersion` và `currentVersion`; client tải lại hồ sơ rồi sửa lại.
2. **Hai request cùng version chạy cùng lúc**: cả hai qua được bước 1. `@Version` vẫn chặn khi flush
   (`UPDATE ... WHERE version = ?` → 0 dòng) → **409** `concurrent-modification`. Chỉ một request thắng.

```json
{
  "type": "https://shoplab.dev/errors/concurrent-modification",
  "title": "Concurrent Modification",
  "status": 409,
  "detail": "Hồ sơ người dùng id = 1 đã bị sửa sau lần bạn đọc (bạn gửi version 0, hiện tại là 1), hãy tải lại rồi sửa lại",
  "instance": "/api/users/1",
  "timestamp": "2026-10-08T03:05:00.123Z",
  "expectedVersion": 0,
  "currentVersion": 1
}
```

### Ví – `/api/wallets`

Mỗi người dùng (tài khoản ACTIVE) có tối đa một ví, số dư không âm. Nạp và chuyển tiền **bắt buộc header `Idempotency-Key`** như `POST /api/orders`: gửi lại cùng key thì nhận lại nguyên văn response lần đầu, tiền không bị nạp / chuyển hai lần.

| Method | Đường dẫn | Thành công | Lỗi có thể gặp |
|---|---|---|---|
| `POST` | `/api/wallets` `{"userId": 1}` | **201** + `Location`, số dư 0 | 409 người dùng đã có ví · 422 người dùng không tồn tại / bị khoá |
| `GET` | `/api/wallets/{id}` | **200** | 404 |
| `POST` | `/api/wallets/{id}/deposits` `{"amount": 1000}` | **200**, ví sau khi nạp | 400 · 404 |
| `POST` | `/api/wallets/transfers` `{"fromWalletId": 1, "toWalletId": 2, "amount": 30}` | **200**, `{amount, from, to}` với số dư hai ví sau khi chuyển | 400 · 404 · 409 không đủ tiền · 422 chuyển cho chính ví đó · 409 `deadlock` (không xảy ra với cách khoá hiện tại) |

Chuyển tiền khoá hai ví theo thứ tự id tăng dần, xem mục *Deadlock* bên dưới.

### Lỗi chung cho mọi endpoint

| Mã | Khi nào |
|---|---|
| 400 | JSON sai cú pháp, tham số sai kiểu |
| 404 | Đường dẫn không tồn tại |
| 405 | Method không hỗ trợ (vd `PUT /api/products/1`) |
| 415 | `Content-Type` không phải `application/json` |
| 500 | Lỗi không lường trước (chi tiết chỉ ghi log, không trả ra ngoài) |

### Trừ kho: một câu UPDATE có điều kiện

`DefaultProductInventory` trừ kho mỗi sản phẩm bằng đúng một câu lệnh, và chỉ nhận khi câu lệnh cập nhật được **đúng 1 dòng**:

```sql
UPDATE products
SET stock = stock - :quantity, version = version + 1, updated_at = now()
WHERE id = :id AND active AND stock >= :quantity
RETURNING id, sku, name, price      -- chụp vào dòng đơn
```

- **Kiểm tra và trừ nằm trong cùng một câu**, nên không có khoảng hở giữa "đọc" và "ghi". Nhiều đơn cùng trừ một sản phẩm thì PostgreSQL cho các câu UPDATE lần lượt khoá dòng: câu đến sau chờ câu trước commit, rồi kiểm tra lại `stock >= :quantity` trên con số mới nhất.
- **Cập nhật 0 dòng** (không tồn tại, ngừng bán hoặc không đủ hàng) thì ném lỗi; transaction của đơn rollback, kể cả phần đã trừ của các sản phẩm trước trong cùng đơn, và không đơn nào được tạo. Lý do được đọc lại sau đó để trả đúng lỗi: `invalid-order` (422) hoặc `insufficient-stock` (409).
- Các sản phẩm của một đơn được trừ theo thứ tự `productId`, nên mọi đơn khoá các dòng theo cùng thứ tự → không deadlock.
- **`version = version + 1`**: câu UPDATE không đi qua entity, nên `Product` đã nạp trước đó trong cùng transaction vẫn giữ số tồn kho cũ. Tăng version để entity cũ đó nếu bị sửa và lưu lại sẽ gặp lỗi xung đột (`@Version`) thay vì ghi đè số tồn kho mới.
- Đây là chỗ duy nhất quy tắc "không bán quá tồn kho" không nằm trong entity: nó nằm trong câu SQL, vì chỉ DB mới kiểm tra và trừ được trong cùng một bước. Ràng buộc `CHECK (stock >= 0)` của bảng là lớp chặn cuối.

Mỗi phần của câu lệnh đều có test giữ. Thử bỏ từng phần thì:

| Bỏ đi | Điều xảy ra (1.000 lượt mua, kho 1) | Test bắt được |
|---|---|---|
| Kiểm tra "đúng 1 dòng" | 1.000 × `201`: **1.000 đơn** cho 1 cái hàng | `FlashSaleIntegrationTests` |
| Điều kiện `stock >= :quantity` | CHECK của DB chặn kho âm, nhưng 999 người nhận `409 data-integrity` thay vì "hết hàng" | `FlashSaleIntegrationTests` |
| `version = version + 1` | `Product` nạp trước đó lưu lại được, ghi đè kho về số cũ | `ProductModuleTests` |

### So với cách "đọc → kiểm tra → trừ ở Java → lưu"

`NaiveStockDeductionTests` so sánh bản thật với một bản "ngây thơ" (`NaiveProductInventory`, chỉ có trong test): đọc tồn kho → kiểm tra còn hàng → trừ ở Java → lưu, không khoá gì. Kịch bản: 8 người cùng mua 1 cái, kho còn 5.

| Cách giữ hàng | Mua được | Lỗi | Kho còn | Vì sao |
|---|---|---|---|---|
| Ngây thơ, lưu bằng `UPDATE products SET stock = ?` | **8** | 0 | **4** | Cả 8 cùng đọc 5, cùng thấy còn hàng, cùng ghi 5 − 1 = 4: lần ghi sau đè lần ghi trước (lost update). Bán vượt 3 cái, mà kho vẫn báo còn 4 |
| Ngây thơ, lưu qua entity (`@Version`) | 1 | 7 xung đột | 4 | `UPDATE ... WHERE id = ? AND version = ?`: người ghi đầu tiên đổi version, 7 người sau không khớp nên rollback. Không bán vượt, nhưng từ chối 7 người dù kho còn 4 |
| Bản thật: `UPDATE ... WHERE stock >= ?`, nhận khi cập nhật đúng 1 dòng | **5** | 3 hết hàng | **0** | Kiểm tra và trừ trong cùng một câu; câu sau chờ câu trước commit rồi kiểm tra lại trên con số mới nhất |

Log SQL của bản ngây thơ cho thấy rõ: 8 câu `SELECT` chạy trước, sau đó mới tới 8 câu `UPDATE`. Trong test, bản ngây thơ được giữ lại giữa bước kiểm tra và bước lưu tới khi cả 8 người đã đọc, để kết quả không tuỳ may rủi; bỏ chỗ giữ lại thì trên máy dev vẫn ra đúng kết quả trên (5/5 lần chạy), vì 8 câu `SELECT` xong trước khi câu `UPDATE` đầu tiên kịp chạy.

### Test bán chớp nhoáng: 1.000 lượt mua, kho còn 1 cái

```bash
./mvnw test -Dtest='FlashSaleIntegrationTests,NaiveFlashSaleTests'
```

Mỗi test tạo một sản phẩm còn 1 cái, rồi `buyAtOnce(productId, 1000)` (trong `IntegrationTestBase`) bắn 1.000 request `POST /api/orders` cùng lúc. Mỗi request có `Idempotency-Key` riêng, nên là 1.000 lần mua khác nhau chứ không phải gửi lại. Request đi qua đủ luồng thật: HTTP → idempotency → kiểm tra người đặt → giữ hàng → tạo đơn. Sau đó test đếm response theo loại kết quả, số đơn trong DB và tồn kho còn lại.

| Test | Giữ hàng bằng | Response | Số đơn | Kho còn |
|---|---|---|---|---|
| `FlashSaleIntegrationTests` | Bản thật: `UPDATE` có điều kiện, nhận khi cập nhật đúng 1 dòng | 1 × `201`, 999 × `409 insufficient-stock` | **1** | 0 |
| `NaiveFlashSaleTests` | Bản ngây thơ: đọc → kiểm tra → trừ ở Java → `UPDATE stock = ?` | 10 × `201`, 990 × `409 insufficient-stock` | **10** | 0 |

- **Bản ngây thơ bán vượt tối đa bằng số connection của pool** (Hikari mặc định 10). Mỗi lượt mua giữ một connection suốt transaction, nên cùng lúc chỉ có 10 lượt đọc được "còn 1 cái" trước khi lượt đầu tiên commit; các lượt sau đều đọc thấy 0. Kho vẫn báo 0 nên nhìn tồn kho không phát hiện được.
- **Không ép thứ tự thì số đơn thay đổi theo từng lần chạy.** 5 lần chạy thử cho 6, 10, 9, 1, 10 đơn; có lần chỉ ra 1 đơn, tức lỗi không lộ ra. Vì vậy `NaiveFlashSaleTests` giữ mỗi lượt mua lại giữa bước kiểm tra và bước lưu tới khi 10 lượt đã cùng đọc kho (trường hợp xấu nhất), để luôn ra 10 đơn.
- **Đếm số đơn thôi chưa đủ.** Thử bỏ điều kiện `stock >= :quantity` thì vẫn chỉ ra 1 đơn (CHECK `stock >= 0` của DB chặn được kho âm), nhưng 999 lượt mua nhận `409 data-integrity` thay vì "hết hàng". Test kiểm tra cả cách phân bố response nên vẫn bắt được.
- Hai test này tắt log SQL (`@TestPropertySource`), vì 1.000 request in ra khoảng 18.000 dòng.

#### Tự chạy với app thật: `scripts/flash-sale.ps1`

Script PowerShell, không cần cài thêm gì: chạy được trên Windows PowerShell 5.1 có sẵn và PowerShell 7+ (cả macOS / Linux). Gửi request song song bằng `HttpClient` của .NET (tối đa `-Parallel` kết nối cùng lúc).

Chạy DB và app (lệnh `./mvnw` trên Windows là `.\mvnw`):

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.arguments="--logging.level.sql=INFO --logging.level.tx=INFO"
```

Rồi ở terminal khác, khi app đã báo `Started ShoplabApplication`:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\flash-sale.ps1                                  # 1000 lượt mua, kho 1, 200 lượt song song
powershell -ExecutionPolicy Bypass -File .\scripts\flash-sale.ps1 -Buyers 300 -Stock 7 -Parallel 50
```

App chạy ở cổng khác thì thêm `-BaseUrl http://localhost:9090`. Trên macOS / Linux: `pwsh ./scripts/flash-sale.ps1`.

Script tạo một người mua và một sản phẩm mới (không đụng dữ liệu cũ), bắn `-Buyers` request `POST /api/orders` với tối đa `-Parallel` request song song, mỗi request một `Idempotency-Key` riêng, rồi in số response theo mã HTTP và loại lỗi, số đơn, tồn kho còn lại:

```
== Bước 4: 1000 lượt mua, 200 lượt chạy song song
   xong sau ~4.7 giây
   Mã HTTP (000 = không kết nối được):
           1 201
         999 409
   Loại lỗi:
         999 insufficient-stock
== Bước 5: đếm đơn và xem kho
   số đơn trong DB : 1 (mong đợi 1)
   response 201    : 1 (mong đợi 1)
   hết hàng        : 999 (mong đợi 999)
   kho còn         : 0 (mong đợi 0)
ĐÚNG: bán đúng 1 cái, các lượt còn lại đều nhận "hết hàng"
```

Mã thoát: `0` nếu bán đúng `min(Stock, Buyers)` cái và mọi lượt còn lại đều nhận "hết hàng"; `1` nếu bán vượt hoặc có lượt nhận lỗi khác (vd bỏ kiểm tra "đúng 1 dòng" thì 1.000 đơn; bỏ điều kiện `stock >= :quantity` thì vẫn 1 đơn nhưng 999 lượt nhận `data-integrity`); `2` nếu không chạy được (app chưa chạy).

### Deadlock: chuyển tiền A→B và B→A cùng lúc

`TransferDeadlockTests` dùng `NaiveWalletTransfer` (chỉ có trong test): chuyển tiền bằng cách khoá hai ví (`SELECT ... FOR UPDATE`) **theo thứ tự tham số**, ví nguồn trước, ví đích sau, với `Thread.sleep(50)` giữa hai lần khoá. Hai lượt chuyển ngược chiều chạy cùng lúc, mỗi lượt một transaction:

```
A→B:  khoá A ── sleep 50ms ── xin khoá B ⏳ (B đang bị B→A giữ)
B→A:  khoá B ── sleep 50ms ── xin khoá A ⏳ (A đang bị A→B giữ)
```

Mỗi bên giữ một khoá và chờ khoá bên kia: không bên nào đi tiếp được. Log thật:

```
04:57:55.340 [virtual-53] select ... from wallets where id=? for no key update   ← B→A khoá B
04:57:55.340 [virtual-51] select ... from wallets where id=? for no key update   ← A→B khoá A
04:57:55.439 [virtual-53] select ... from wallets where id=? for no key update   ← B→A xin khoá A, chờ
04:57:55.439 [virtual-51] select ... from wallets where id=? for no key update   ← A→B xin khoá B, chờ
04:57:56.449 [virtual-51] ERROR: deadlock detected
  Detail: Process 534 waits for ShareLock on transaction 41340; blocked by process 530.
          Process 530 waits for ShareLock on transaction 41339; blocked by process 534.
04:57:56.457 [virtual-51] ROLLBACK transfer A→B
04:57:56.471 [virtual-53] update wallets set balance=? ... where id=? and version=?
04:57:56.482 [virtual-53] COMMIT   transfer B→A
```

- PostgreSQL chỉ đi tìm deadlock khi một bên đã chờ khoá quá `deadlock_timeout` (mặc định **1 giây**), rồi huỷ một transaction với lỗi `40P01`. Bên còn lại lấy được khoá và chạy xong. Bên nào bị huỷ là ngẫu nhiên.
- Không mất tiền: lượt bị huỷ rollback toàn bộ, tổng số dư hai ví không đổi. Nhưng cả hai lượt đều bị treo khoảng 1 giây, và một lượt thất bại dù hợp lệ.
- Phía Spring nhận `CannotAcquireLockException` (một `PessimisticLockingFailureException`). `GlobalExceptionHandler` đọc mã `40P01` và trả `409 deadlock` + `Retry-After`, tách riêng khỏi `409 lock-timeout`.
- `sleep(50)` để chắc chắn cả hai bên đã giữ khoá đầu tiên trước khi xin khoá thứ hai. Bỏ đi thì trên máy dev vẫn deadlock 10/10 lần, nhưng không đảm bảo trên máy khác.

#### Sửa: khoá hai ví theo thứ tự id tăng dần

`WalletService.transfer` (code thật) khoá ví **id nhỏ trước, id lớn sau**, bất kể chiều chuyển:

```java
long firstId = Math.min(fromWalletId, toWalletId);
Wallet first = lock(firstId);                                 // id nhỏ trước
Wallet second = lock(Math.max(fromWalletId, toWalletId));     // id lớn sau
Wallet from = firstId == fromWalletId ? first : second;
Wallet to = firstId == fromWalletId ? second : first;
from.withdraw(amount);
to.deposit(amount);
```

A→B và B→A đều xin khoá ví A trước. Lượt đến sau chờ ngay ở khoá đầu tiên, khi chưa giữ khoá nào, nên không thể có cảnh mỗi bên giữ một khoá rồi chờ nhau. Cùng điều kiện như bản ngây thơ (chờ 50ms giữa hai lần khoá, test chèn bằng một proxy bọc `WalletRepository` để code thật không chứa `sleep`):

| Cách khoá | Kết quả A→B 10 và B→A 30 cùng lúc (mỗi ví 100) | Thời gian |
|---|---|---|
| Theo thứ tự tham số | 1 lượt chuyển xong, 1 lượt bị huỷ vì deadlock | ~1,1 giây |
| Theo thứ tự id tăng dần | Cả 2 lượt chuyển xong: A = 120, B = 80 | ~0,2–0,5 giây |

Quy tắc chung: chỗ nào khoá nhiều dòng trong cùng một transaction thì mọi nơi phải khoá theo **cùng một thứ tự** (ở đây là id tăng dần). Trừ kho cho đơn nhiều sản phẩm cũng theo quy tắc này (theo `productId`).

#### Tự chạy với app thật: `scripts/transfer-deadlock.ps1`

Cùng kịch bản với `TransferDeadlockTests`, nhưng gọi API của app đang chạy. Mỗi vòng: tạo hai người dùng A, B, tạo ví và nạp mỗi ví 100, rồi gửi **cùng lúc** 1 lượt A→B 10 và 1 lượt B→A 30 (`POST /api/wallets/transfers`, mỗi lượt một `Idempotency-Key`), in lượt nào xong trước, sau bao lâu, rồi đọc lại số dư.

```powershell
# cần app đang chạy (.\mvnw spring-boot:run)
powershell -ExecutionPolicy Bypass -File .\scripts\transfer-deadlock.ps1             # 1 vòng
powershell -ExecutionPolicy Bypass -File .\scripts\transfer-deadlock.ps1 -Runs 10    # 10 vòng, mỗi vòng hai ví mới
```

```
== Vòng 1/1
   ví A (id 4802) = 100, ví B (id 4803) = 100
   gửi cùng lúc: A->B 10 | B->A 30
   A->B 10  → 200 sau   50 ms   (sau lượt này: ví nguồn = 90, ví đích = 110)
   B->A 30  → 200 sau   66 ms   (sau lượt này: ví nguồn = 80, ví đích = 120)
   số dư cuối: A = 120 (mong đợi 120), B = 80 (mong đợi 80), tổng 200
   ĐÚNG

ĐÚNG (1/1 vòng): A->B và B->A cùng lúc đều chuyển xong, không deadlock, A = 120, B = 80
```

Lượt B→A trả về số dư sau khi A→B đã chuyển xong (ví nguồn 110 → 80, ví đích 90 → 120): nó chờ A→B nhả khoá rồi mới chạy. Mã thoát `0` nếu mọi vòng đều đúng; `1` nếu có vòng sai; `2` nếu không chạy được (app chưa chạy).

**Nên chạy nhiều vòng.** App thật không có `sleep(50)` giữa hai lần khoá như trong test, nên chỉ một cặp chuyển tiền thì hai lượt không phải lúc nào cũng chồng lên nhau. Chạy 20 vòng với một bản app cố ý khoá theo thứ tự tham số:

```
   B->A 30  → 409 deadlock sau 1044 ms: Thao tác bị huỷ vì tranh chấp khoá với một thao tác khác (deadlock), hãy thử lại
   ...
SAI: 13/20 vòng không như mong đợi, 13 lượt bị huỷ vì deadlock (app khoá hai ví theo thứ tự khác nhau?)
```

13/20 vòng bị deadlock (lượt thua chờ đúng ~1 giây rồi nhận `409 deadlock`), 7 vòng còn lại "qua" nhờ may. Một vòng có thể bỏ sót lỗi; `-Runs 10` thì gần như chắc chắn bắt được. Muốn xem chi tiết từng bước khoá của bản sai, chạy `.\mvnw test -Dtest=TransferDeadlockTests` và đọc log (`BEGIN` / `SELECT ... FOR UPDATE` / `deadlock detected` / `ROLLBACK`).

### Cách hoạt động của Idempotency

Bảng `idempotency_keys`: `idem_key` (khoá chính), `request_hash`, `status`, `response_status`, `response_headers` (JSONB, vd `Location`), `response_body` (TEXT, nguyên văn chuỗi JSON đã trả), `created_at`.

`request_hash` là SHA-256 của JSON dạng chuẩn hoá của request (`CreateOrderCommand`: `userId`, các dòng đã gộp và sắp theo `productId`). Băm JSON chứ không tự ghép chuỗi, nên hai request khác nhau không thể trùng mã băm.

Ghi key, tạo đơn, trừ kho và lưu response diễn ra trong **một transaction**:

```
BEGIN
  INSERT key ... ON CONFLICT DO NOTHING   -- request trùng key phải chờ ở đây
  UPDATE products ... WHERE stock >= ?    -- trừ kho có điều kiện, 0 dòng → huỷ cả đơn
  tạo order
  UPDATE key → COMPLETED + status, header, body của response
COMMIT
```

- Lỗi ở bất kỳ bước nào → rollback cả key lẫn đơn → client gửi lại với cùng key được.
- Nhiều request cùng key đến cùng lúc → chỉ một request tạo đơn, các request còn lại nhận bản replay.
- Bản replay dựng từ chuỗi đã lưu, giống hệt lần đầu từng ký tự. Không đọc lại vào class `OrderResponse`, nên sửa DTO sau này không làm hỏng replay của các key còn hạn.
- Chờ khoá tối đa 5 giây, cấu hình chung cho mọi connection (xem *Cấu hình đáng chú ý*).
- Key được xoá sau 24 giờ (job chạy mỗi giờ).

---

## 3. Quy ước lỗi: ProblemDetail (RFC 9457)

### Định dạng

```json
{
  "type": "https://shoplab.dev/errors/insufficient-stock",
  "title": "Insufficient Stock",
  "status": 409,
  "detail": "Sản phẩm AO-THUN-001 không đủ hàng: cần 999, còn 8",
  "instance": "/api/orders",
  "timestamp": "2026-10-06T07:04:00.123Z",
  "sku": "AO-THUN-001",
  "requested": 999,
  "available": 8
}
```

| Trường | Ý nghĩa | Dành cho |
|---|---|---|
| `type` | URI định danh **loại** lỗi, cố định | Code phía client rẽ nhánh |
| `title` | Tên ngắn của loại lỗi, cố định | Người đọc / log |
| `status` | Trùng với HTTP status | Cả hai |
| `detail` | Mô tả cụ thể **lần** lỗi này, có thể đổi câu chữ | Người đọc |
| `instance` | Đường dẫn request gây lỗi | Debug |
| `timestamp`, `errors`, … | Trường mở rộng | Bổ sung ngữ cảnh |

Lỗi validation có thêm `errors` theo từng trường:

```json
{
  "type": "https://shoplab.dev/errors/validation",
  "title": "Validation Failed",
  "status": 400,
  "detail": "Dữ liệu gửi lên không hợp lệ",
  "errors": {
    "userId": "must not be null",
    "items[0].quantity": "must be greater than or equal to 1"
  }
}
```

### Danh sách `type`

Tiền tố: `https://shoplab.dev/errors/`

| `type` | Mã | Ý nghĩa |
|---|---|---|
| `validation` | 400 | Dữ liệu gửi lên không hợp lệ |
| `invalid-sort` | 400 | Sắp xếp theo trường không tồn tại |
| `invalid-idempotency-key` | 400 | Key sai định dạng |
| `product-not-found` | 404 | Không tìm thấy sản phẩm |
| `order-not-found` | 404 | Không tìm thấy đơn hàng |
| `user-not-found` | 404 | Không tìm thấy người dùng |
| `wallet-not-found` | 404 | Không tìm thấy ví |
| `duplicate-sku` | 409 | SKU đã tồn tại |
| `duplicate-email` / `duplicate-username` | 409 | Email / username đã được đăng ký |
| `account-disabled` | 409 | Tài khoản đã vô hiệu hoá, không khoá / mở được |
| `duplicate-wallet` | 409 | Người dùng đã có ví |
| `insufficient-balance` | 409 | Ví không đủ tiền |
| `insufficient-stock` | 409 | Không đủ hàng |
| `concurrent-modification` | 409 | Bản ghi vừa bị request khác sửa (`@Version`), hoặc `version` client gửi đã cũ (kèm `expectedVersion`, `currentVersion`) |
| `data-integrity` | 409 | Vi phạm ràng buộc dữ liệu (vd xoá sản phẩm đã có trong đơn) |
| `lock-timeout` / `idempotency-in-progress` | 409 | Đang có request khác xử lý cùng dữ liệu, kèm `Retry-After` |
| `deadlock` | 409 | PostgreSQL huỷ thao tác vì deadlock với thao tác khác (`40P01`), kèm `Retry-After`, gửi lại được |
| `idempotency-key-reused` | 422 | Key đã dùng với nội dung khác |
| `invalid-order` | 422 | Đơn hàng không xử lý được (người dùng không tồn tại / bị khoá, sản phẩm không tồn tại / ngừng bán) |
| `user-unavailable` | 422 | Tạo ví cho người dùng không tồn tại / không ACTIVE |
| `invalid-transfer` | 422 | Chuyển tiền cho chính ví nguồn |
| `internal` | 500 | Lỗi hệ thống |

Các lỗi có sẵn của Spring MVC (JSON sai, thiếu header, 404 đường dẫn, 405, 415) dùng `type` mặc định `about:blank` nhưng vẫn cùng cấu trúc và có `timestamp`.

### Lý do chọn quy ước này

1. **Một định dạng cho mọi lỗi.** Client chỉ cần một hàm xử lý lỗi cho mọi endpoint, mọi mã từ 400 đến 500. Toàn bộ do một `@RestControllerAdvice` (`GlobalExceptionHandler`) đảm nhận, kể cả lỗi có sẵn của Spring và lỗi không lường trước.
2. **Theo chuẩn, không tự chế.** RFC 9457 là chuẩn IETF, được Spring hỗ trợ sẵn (`ProblemDetail`). Người mới hay đối tác tích hợp không cần học một định dạng riêng.
3. **Tách phần cho máy và phần cho người.** Client rẽ nhánh theo `type` (ổn định); `detail` có thể sửa câu chữ hay dịch mà không làm hỏng client. Không ai phải so khớp chuỗi message.
4. **Nhận biết bằng `Content-Type`.** `application/problem+json` cho biết ngay đây là body lỗi theo chuẩn.
5. **Mở rộng mà không phá chuẩn.** Thêm `errors`, `sku`, `requested`, `available`, `timestamp` khi cần; client chỉ đọc trường chuẩn vẫn chạy.
6. **An toàn.** Lỗi 500 chỉ trả câu chung chung; stack trace và câu SQL chỉ ghi vào log.

### Quy ước chọn mã trạng thái

| Mã | Dùng khi |
|---|---|
| **400** | Request sai **hình thức**: JSON hỏng, thiếu header, sai kiểu, vi phạm validation |
| **404** | Tài nguyên trong **path** không tồn tại |
| **409** | Request hợp lệ nhưng **xung đột với trạng thái hiện tại**: trùng SKU, hết hàng, đang bị khoá, bị sửa đồng thời. Thử lại sau khi trạng thái thay đổi có thể thành công |
| **422** | Request đúng hình thức nhưng **không thể xử lý về mặt ngữ nghĩa**: tham chiếu sản phẩm không tồn tại trong body, dùng lại key với nội dung khác. Gửi lại y hệt sẽ luôn lỗi |
| **500** | Lỗi phía server, không phải do client |

Phân biệt 404 và 422: `GET /api/products/999` → **404** vì tài nguyên được chỉ đích danh trong path không tồn tại; còn `productId: 999` nằm trong body đơn hàng → **422** vì bản thân `/api/orders` vẫn tồn tại, chỉ là nội dung không xử lý được.

---

## 4. Kiến trúc: modular monolith (Spring Modulith)

Một ứng dụng, một database, nhưng chia thành các module có ranh giới rõ ràng. Mỗi package con trực tiếp của `com.shoplab` là một module, khai báo trong `package-info.java` của nó bằng `@ApplicationModule`.

### Các module

| Module | Lo việc gì | API cho module khác | Được phụ thuộc vào |
|---|---|---|---|
| `common` | Phần dùng chung (shared kernel) | `ApiException`, `BaseEntity`, `AuditedEntity`, `DbConstraints`, `ValidationPatterns` | (không module nào) |
| `product` | Danh mục sản phẩm, tồn kho | `ProductInventory` (giữ hàng), `ReservedItem`, `ProductUnavailableException`, `InsufficientStockException`; `ProductReferences` (module khác cài đặt) | `common` |
| `idempotency` | Chạy request ghi đúng một lần theo `Idempotency-Key` | `IdempotencyService` và các exception của nó | `common` |
| `order` | Đơn hàng | (chưa có) | `common`, `product`, `user`, `idempotency` |
| `user` | Người dùng: hồ sơ + tài khoản đăng nhập | `UserDirectory` (tra người dùng đang ACTIVE), `UserSummary`, `UserUnavailableException` | `common` |
| `wallet` | Ví tiền của người dùng (số dư không âm): tạo ví, nạp tiền, chuyển tiền giữa hai ví | (chưa có) | `common`, `user`, `idempotency` |

```
order   ──► product, user, idempotency
wallet  ──► user, idempotency
mọi module ──► common
```

Phụ thuộc chỉ đi một chiều: `product`, `user`, `idempotency` không phụ thuộc module nghiệp vụ nào khác (ngoài `common`).

### Bố cục bên trong một module

```
product/
├── package-info.java       @ApplicationModule(allowedDependencies = "common")
├── ProductInventory.java   API: chỉ những gì module khác được dùng (interface, record, exception)
├── ...
├── internal/               nội bộ: entity, repository, service, command, cài đặt của API
└── web/                    nội bộ: controller và DTO của REST API
```

- **Package gốc của module là API.** Module khác chỉ được dùng các kiểu ở đây.
- **`internal/` và `web/` là nội bộ.** Module khác không được dùng, kể cả khi class là `public`.
- Bên trong module: `web` → `internal` → API. Tầng `internal` không biết gì về HTTP hay DTO.

### Quy tắc (kiểm tra tự động bởi `ModularityTests`, `DatabaseModularityTests`)

Vi phạm thì test đỏ và chỉ rõ chỗ vi phạm.

- **Ranh giới module (Spring Modulith `verify()`):** không vòng phụ thuộc; chỉ dùng API của module khác; chỉ phụ thuộc những module khai báo trong `allowedDependencies`.
- **Phân tầng trong module (ArchUnit):** chỉ tầng `web` dùng controller và DTO; repository không `public`, nên dữ liệu của module chỉ được truy cập từ code cùng package.
- **Ranh giới module ở DB:** mỗi bảng khai báo thuộc đúng một module; không có khoá ngoại nối bảng của hai module khác nhau.

Các quy ước khác:
- `order` lấy người đặt qua API `UserDirectory.requireActiveUser(...)` (chỉ tài khoản ACTIVE mới đặt được) và chụp lại tên, email; kiểm tra này chạy trước khi giữ hàng.
- `order` giữ hàng qua API `ProductInventory.reserveStock(...)`: module product trừ kho bằng một câu UPDATE có điều kiện ngay trong transaction của đơn (xem *Trừ kho: một câu UPDATE có điều kiện*). Dòng đơn tham chiếu sản phẩm bằng `productId` và chụp lại `sku`, tên, giá tại thời điểm đặt, nên sửa sản phẩm không làm đổi đơn cũ.
- Entity tự chuẩn hoá và tự kiểm tra dữ liệu của mình (`Product`, `Order`, `User`, `Account`). Service nhận command (`CreateProductCommand`, `CreateOrderCommand`, `RegisterUserCommand`...) và trả entity; controller đổi DTO web ↔ command / entity.
- Entity kế thừa `common.BaseEntity` (id lấy từ sequence của bảng, khai báo bằng `@SequenceGenerator` trên class) hoặc `common.AuditedEntity` (thêm `version`, `createdAt`, `updatedAt` do Spring Data auditing điền).
- Lỗi nghiệp vụ kế thừa `common.ApiException` (tự mang status, `type`, `title`). `GlobalExceptionHandler` không import exception của module nào.
- Lỗi ràng buộc DB do service sở hữu dữ liệu tự dịch theo tên constraint, ví dụ `uk_products_sku` → `duplicate-sku`, `uk_users_email` → `duplicate-email`.
- `User` là gốc của `Account`: tạo, lưu, nạp cùng nhau (cascade), chỉ có `UserRepository`.

### Giao tiếp giữa các module

- **Gọi API trực tiếp** khi việc đó phải xong ngay, trong cùng transaction. Ví dụ đặt hàng phải giữ hàng thành công thì mới tạo đơn.
- **Đảo chiều phụ thuộc** khi module được dùng cần hỏi ngược module dùng nó. Ví dụ trước khi xoá sản phẩm, `product` cần biết sản phẩm đã có trong đơn chưa, nhưng `product` không được phụ thuộc `order` (sẽ thành vòng). Vì vậy `product` định nghĩa interface `ProductReferences` trong API của mình, `order` cài đặt nó (`OrderProductReferences`), Spring đưa mọi cài đặt vào `ProductService`. Phụ thuộc vẫn một chiều `order → product`.
- **Dùng event** cho việc phụ, chạy sau và không được làm hỏng việc chính, ví dụ gửi email xác nhận đơn. Hiện chưa có trường hợp nào như vậy. Khi cần, module phát event qua `ApplicationEventPublisher`, module nhận lắng nghe bằng `@ApplicationModuleListener`, và thêm Event Publication Registry của Spring Modulith để event không bị mất khi app dừng giữa chừng.

### Dữ liệu

Các module dùng chung một schema. Mỗi module chỉ đọc/ghi bảng của mình trong code: `product` → `products`; `order` → `orders`, `order_items`; `idempotency` → `idempotency_keys`; `user` → `users`, `accounts`; `wallet` → `wallets`.

Khoá ngoại chỉ nối các bảng **trong cùng một module** (`order_items → orders`, `accounts → users`). Giữa hai module chỉ lưu id (vd `order_items.product_id`, `orders.user_id`), không có khoá ngoại (V10 bỏ `fk_order_items_product`). Nhờ vậy mỗi module có thể đổi bảng, tách schema hay tách DB riêng mà không kéo module khác theo. Toàn vẹn dữ liệu giữa các module do code giữ:
- Dòng đơn đã chụp `sku`, tên, giá, và đơn đã chụp tên, email người đặt, nên không cần đọc lại `products` hay `users`.
- Chưa có API xoá người dùng, nên chưa cần chặn xoá người dùng đã có đơn. Khi thêm, làm giống `ProductReferences`.
- Không xoá được sản phẩm đã có trong đơn: `ProductService.delete` khoá dòng sản phẩm (`FOR UPDATE`), rồi hỏi các module qua `ProductReferences`. Tạo đơn cũng khoá dòng này khi giữ hàng (câu UPDATE trừ kho), nên đơn đang tạo dở không lọt qua được: lệnh xoá chờ đơn commit rồi mới kiểm tra (→ 409); còn nếu lệnh xoá đến trước thì đơn đến sau không còn thấy sản phẩm (→ 422 `invalid-order`).

### Tài liệu module sinh tự động

`./mvnw test -Dtest=ModularityTests` sinh vào `target/spring-modulith-docs/`:
- `components.puml`: sơ đồ C4 các module và quan hệ;
- `module-*.puml`, `module-*.adoc`: sơ đồ và mô tả từng module.

### Cấu trúc thư mục

```
src/main/java/com/shoplab/
├── ShoplabApplication.java   @Modulithic(sharedModules = "common")
├── common/         ApiException, BaseEntity, AuditedEntity, DbConstraints, ValidationPatterns
│   ├── config/     JpaAuditingConfig, SchedulingConfig, TransactionLogging
│   └── web/        GlobalExceptionHandler
├── product/        ProductInventory, ReservedItem, ProductUnavailableException, InsufficientStockException,
│   │               ProductReferences
│   ├── internal/   Product, ProductRepository, ProductService, DefaultProductInventory,
│   │               CreateProductCommand, UpdateProductCommand, các exception nội bộ
│   └── web/        ProductController, CreateProductRequest, PatchProductRequest, ProductResponse
├── idempotency/    IdempotencyService, các exception của nó
│   └── internal/   DefaultIdempotencyService, IdempotencyStore, IdempotencyRecord, IdempotencyStatus,
│                   RequestFingerprint, IdempotencyCleanupJob
├── order/
│   ├── internal/   Order, OrderItem, OrderStatus, OrderRepository, OrderService, CreateOrderCommand,
│   │               OrderProductReferences, các exception nội bộ
│   └── web/        OrderController, CreateOrderRequest, OrderResponse, OrderSummaryResponse
├── user/           UserDirectory, UserSummary, UserUnavailableException
│   ├── internal/   User, Account, AccountStatus, UserRepository, UserService, DefaultUserDirectory,
│   │               PasswordConfig, RegisterUserCommand, UpdateProfileCommand, các exception nội bộ
│   └── web/        UserController, RegisterUserRequest, UpdateProfileRequest, UserResponse
└── wallet/
    ├── internal/   Wallet, WalletRepository, WalletService (tạo ví, nạp tiền, chuyển tiền khoá theo id tăng dần),
    │               DepositCommand, TransferCommand, TransferResult, các exception nội bộ
    └── web/        WalletController, CreateWalletRequest, DepositRequest, TransferRequest,
                    WalletResponse, TransferResponse
src/main/resources/db/migration/
├── V1__init.sql                              products, orders, order_items
├── V2__add_product_category.sql              cột category
├── V3__create_idempotency_keys.sql           bảng idempotency_keys
├── V4__idempotency_store_response_body.sql   lưu nội dung phản hồi (JSONB)
├── V5__create_users_and_accounts.sql         bảng users, accounts
├── V6__order_items_product_snapshot.sql      chụp sku, tên sản phẩm vào dòng đơn
├── V7__idempotency_store_raw_response.sql    lưu nguyên văn response (body TEXT + header)
├── V8__ids_from_sequence.sql                 id lấy từ sequence (bước 50) thay vì IDENTITY
├── V9__user_ids_from_sequence.sql            như V8, cho users, accounts
├── V10__drop_cross_module_fk.sql             bỏ khoá ngoại chéo module order_items → products
├── V11__orders_user_id.sql                   orders.user_id (không khoá ngoại) + index cho danh sách đơn
└── V12__create_wallets.sql                   bảng wallets (module wallet)
src/test/java/com/shoplab/
├── TestcontainersConfiguration.java          PostgreSQL 17 (cùng bản với docker-compose)
├── TestShoplabApplication.java
├── IntegrationTestBase.java                  nền chung cho integration test qua HTTP
├── Concurrently.java                         chạy N tác vụ cùng lúc (virtual thread + CountDownLatch)
├── SqlLoggingTests.java                      log SQL và BEGIN / COMMIT / ROLLBACK in đúng thứ tự
├── ModularityTests.java                      kiểm tra cấu trúc module, sinh tài liệu module
├── DatabaseModularityTests.java              bảng thuộc module nào, không có khoá ngoại chéo module
├── common/web/         GlobalExceptionHandlerTests
├── idempotency/internal/ RequestFingerprintTest
├── order/              OrderModuleTests
│   ├── internal/       OrderTest, CreateOrderCommandTest
│   └── web/            OrderApiIntegrationTests, OrderIdempotencyIntegrationTests, FlashSaleIntegrationTests
├── product/            ProductModuleTests
│   ├── internal/       ProductTest,
│   │                   NaiveProductInventory + NaiveStockDeductionTests, NaiveFlashSaleTests
│   │                   (bản trừ kho ngây thơ, để so sánh)
│   └── web/            ProductApiIntegrationTests
├── user/               UserModuleTests
│   ├── internal/       UserTest
│   └── web/            UserApiIntegrationTests
└── wallet/             WalletModuleTests
    ├── internal/       NaiveWalletTransfer + TransferDeadlockTests (deadlock khi khoá theo tham số,
    │                   không deadlock khi khoá theo id)
    └── web/            WalletApiIntegrationTests
```