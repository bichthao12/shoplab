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
| Test slice | `ProductRepositoryTests` (`@DataJpaTest`), `GlobalExceptionHandlerTests` (`@WebMvcTest`) | Chỉ một tầng |
| Test riêng từng module | `ProductModuleTests`, `OrderModuleTests`, `UserModuleTests` (`@ApplicationModuleTest`) | Chỉ một module (kèm `common`); API của module khác được mock |
| Integration test | `OrderIdempotencyIntegrationTests`, `OrderApiIntegrationTests`, `ProductApiIntegrationTests`, `UserApiIntegrationTests`, `SqlLoggingTests` | Cả app trên cổng ngẫu nhiên + PostgreSQL thật (Testcontainers), gồm cả kịch bản đồng thời và rollback |

Kịch bản đồng thời dùng `Concurrently.run(n, i -> ...)`: chạy N tác vụ, mỗi tác vụ trên một virtual thread, và một `CountDownLatch(N)` làm vạch xuất phát (mỗi luồng `countDown()` rồi `await()`), nên không tác vụ nào chạy trước khi đủ N luồng sẵn sàng. Kết quả trả theo thứ tự `i`; quá 60 giây thì báo `TimeoutException` và ngắt các tác vụ còn chạy.
| Test cấu trúc | `ModularityTests` (Spring Modulith + ArchUnit), `DatabaseModularityTests` | Đọc bytecode, kiểm tra ranh giới module và phân tầng trong module; đọc schema, kiểm tra không có khoá ngoại chéo module |

### Test thủ công

- **Postman:** import `shoplab.postman_collection.json` → *Run collection* (chạy đúng thứ tự). Biến `baseUrl` mặc định `http://localhost:8080`.
- **File `.http`** (IntelliJ / VS Code REST Client): `products.http`, `users.http`.
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
04:12:01.452 [exec-1] org.hibernate.SQL  : select ... from products p1_0 where p1_0.id in (?) order by p1_0.id for no key update of p1_0
04:12:01.510 [exec-1] org.hibernate.SQL  : insert into orders ...
04:12:01.516 [exec-1] org.hibernate.SQL  : insert into order_items ...
04:12:01.521 [exec-1] org.hibernate.SQL  : update products set ... stock=? ...
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
| `PATCH` | `/api/users/{id}` | **200** (sửa `fullName`, `phone`; `"phone": ""` để xoá) | 400 · 404 · 409 bị sửa đồng thời |
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

### Lỗi chung cho mọi endpoint

| Mã | Khi nào |
|---|---|
| 400 | JSON sai cú pháp, tham số sai kiểu |
| 404 | Đường dẫn không tồn tại |
| 405 | Method không hỗ trợ (vd `PUT /api/products/1`) |
| 415 | `Content-Type` không phải `application/json` |
| 500 | Lỗi không lường trước (chi tiết chỉ ghi log, không trả ra ngoài) |

### Cách hoạt động của Idempotency

Bảng `idempotency_keys`: `idem_key` (khoá chính), `request_hash`, `status`, `response_status`, `response_headers` (JSONB, vd `Location`), `response_body` (TEXT, nguyên văn chuỗi JSON đã trả), `created_at`.

`request_hash` là SHA-256 của JSON dạng chuẩn hoá của request (`CreateOrderCommand`: `userId`, các dòng đã gộp và sắp theo `productId`). Băm JSON chứ không tự ghép chuỗi, nên hai request khác nhau không thể trùng mã băm.

Ghi key, tạo đơn, trừ kho và lưu response diễn ra trong **một transaction**:

```
BEGIN
  INSERT key ... ON CONFLICT DO NOTHING   -- request trùng key phải chờ ở đây
  SELECT products ... FOR UPDATE          -- khoá sản phẩm, chống bán vượt tồn kho
  trừ kho, tạo order
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
| `duplicate-sku` | 409 | SKU đã tồn tại |
| `duplicate-email` / `duplicate-username` | 409 | Email / username đã được đăng ký |
| `account-disabled` | 409 | Tài khoản đã vô hiệu hoá, không khoá / mở được |
| `insufficient-stock` | 409 | Không đủ hàng |
| `concurrent-modification` | 409 | Bản ghi vừa bị request khác sửa (`@Version`) |
| `data-integrity` | 409 | Vi phạm ràng buộc dữ liệu (vd xoá sản phẩm đã có trong đơn) |
| `lock-timeout` / `idempotency-in-progress` | 409 | Đang có request khác xử lý cùng dữ liệu, kèm `Retry-After` |
| `idempotency-key-reused` | 422 | Key đã dùng với nội dung khác |
| `invalid-order` | 422 | Đơn hàng không xử lý được (người dùng không tồn tại / bị khoá, sản phẩm không tồn tại / ngừng bán) |
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

```
order ──► product ──────┐
  ├─────► user ─────────┼──► common
  └─────► idempotency ──┘
```

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
- `order` giữ hàng qua API `ProductInventory.reserveStock(...)`: module product khoá, kiểm tra và trừ kho ngay trong transaction của đơn. Dòng đơn tham chiếu sản phẩm bằng `productId` và chụp lại `sku`, tên, giá tại thời điểm đặt, nên sửa sản phẩm không làm đổi đơn cũ.
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

Các module dùng chung một schema. Mỗi module chỉ đọc/ghi bảng của mình trong code: `product` → `products`; `order` → `orders`, `order_items`; `idempotency` → `idempotency_keys`; `user` → `users`, `accounts`.

Khoá ngoại chỉ nối các bảng **trong cùng một module** (`order_items → orders`, `accounts → users`). Giữa hai module chỉ lưu id (vd `order_items.product_id`, `orders.user_id`), không có khoá ngoại (V10 bỏ `fk_order_items_product`). Nhờ vậy mỗi module có thể đổi bảng, tách schema hay tách DB riêng mà không kéo module khác theo. Toàn vẹn dữ liệu giữa các module do code giữ:
- Dòng đơn đã chụp `sku`, tên, giá, và đơn đã chụp tên, email người đặt, nên không cần đọc lại `products` hay `users`.
- Chưa có API xoá người dùng, nên chưa cần chặn xoá người dùng đã có đơn. Khi thêm, làm giống `ProductReferences`.
- Không xoá được sản phẩm đã có trong đơn: `ProductService.delete` khoá dòng sản phẩm (`FOR UPDATE`), rồi hỏi các module qua `ProductReferences`. Tạo đơn cũng khoá dòng này khi giữ hàng, nên đơn đang tạo dở không lọt qua được: lệnh xoá chờ đơn commit rồi mới kiểm tra (→ 409); còn nếu lệnh xoá đến trước thì đơn đến sau không còn thấy sản phẩm (→ 422 `invalid-order`).

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
└── user/           UserDirectory, UserSummary, UserUnavailableException
    ├── internal/   User, Account, AccountStatus, UserRepository, UserService, DefaultUserDirectory,
    │               PasswordConfig, RegisterUserCommand, UpdateProfileCommand, các exception nội bộ
    └── web/        UserController, RegisterUserRequest, UpdateProfileRequest, UserResponse
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
└── V11__orders_user_id.sql                   orders.user_id (không khoá ngoại) + index cho danh sách đơn
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
│   └── web/            OrderApiIntegrationTests, OrderIdempotencyIntegrationTests
├── product/            ProductModuleTests
│   ├── internal/       ProductTest, ProductRepositoryTests
│   └── web/            ProductApiIntegrationTests
└── user/               UserModuleTests
    ├── internal/       UserTest
    └── web/            UserApiIntegrationTests
```