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
| Integration test | `OrderIdempotencyIntegrationTests`, `OrderApiIntegrationTests`, `ProductApiIntegrationTests`, `UserApiIntegrationTests`, `SqlLoggingTests`, `NaiveStockDeductionTests`, `FlashSaleIntegrationTests`, `NaiveFlashSaleTests`, `TransferDeadlockTests`, `SelfInvocationTrapTests`, `WalletApiIntegrationTests` | Cả app trên cổng ngẫu nhiên + PostgreSQL thật (Testcontainers), gồm cả kịch bản đồng thời và rollback |

Kịch bản đồng thời dùng `Concurrently.run(n, i -> ...)`: chạy N tác vụ, mỗi tác vụ trên một virtual thread, và một `CountDownLatch(N)` làm vạch xuất phát (mỗi luồng `countDown()` rồi `await()`), nên không tác vụ nào chạy trước khi đủ N luồng sẵn sàng. Kết quả trả theo thứ tự `i`; quá 60 giây thì báo `TimeoutException` và ngắt các tác vụ còn chạy.
| Test cấu trúc | `ModularityTests` (Spring Modulith + ArchUnit), `DatabaseModularityTests` | Đọc bytecode, kiểm tra ranh giới module và phân tầng trong module; đọc schema, kiểm tra không có khoá ngoại chéo module |

### Test thủ công

- **Postman:** import `shoplab.postman_collection.json` → *Run collection* (chạy đúng thứ tự). Biến `baseUrl` mặc định `http://localhost:8080`.
- **File `.http`** (IntelliJ / VS Code REST Client): `products.http`, `users.http`.
- **Bán chớp nhoáng với app đang chạy:** `scripts\flash-sale.ps1` (xem mục *Test bán chớp nhoáng*).
- **Deadlock chuyển tiền:** `scripts\transfer-deadlock.ps1` (xem mục *Deadlock*).
- **Lost update khi sửa hồ sơ:** `scripts\profile-lost-update.ps1` (xem mục *Sửa hồ sơ: bắt buộc gửi `version`*).
- **Bẫy gọi nội bộ `@Transactional`:** `scripts\self-invocation-trap.ps1` (chạy test, cần Docker; xem mục *Bẫy @Transactional: gọi nội bộ*).
- **Lost update khi sửa sản phẩm:** `scripts\product-lost-update.ps1` (xem mục *Sửa sản phẩm: bắt buộc gửi `version`*).
- **Nhập / trừ tồn kho cùng lúc với đơn hàng, gửi lại cùng key:** `scripts\stock-adjustment.ps1` (xem mục *Điều chỉnh tồn kho*).
- **Đăng ký trùng email / username cùng lúc:** `scripts\duplicate-email.ps1` (xem mục *Đăng ký trùng gửi cùng lúc*).
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
| `spring.datasource.hikari.connection-init-sql` | `SET lock_timeout = '5s'` | Không request nào chờ khoá DB quá 5 giây (→ 409 + `Retry-After`), thay vì giữ connection chờ vô hạn. Chạy mỗi khi Hikari mở connection mới, nên áp dụng cho mọi connection trong pool. Test: `OrderIdempotencyIntegrationTests.keyHeldByAnotherTransaction_returns409AfterLockTimeout` (bỏ dòng này thì request chờ mãi tới khi client hết giờ) |
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
| `PATCH` | `/api/products/{id}` | **200** (chỉ sửa trường được gửi, trừ `stock`; bắt buộc gửi `version` đã đọc) | 400 dữ liệu không hợp lệ / thiếu `version` / có `stock` · 404 · 409 trùng SKU / sản phẩm đã bị sửa sau lần đọc |
| `POST` | `/api/products/{id}/stock-adjustments` (bắt buộc header `Idempotency-Key`) | **200** với sản phẩm sau khi cộng / trừ tồn kho | 400 · 404 · 409 trừ quá tồn kho · 422 key đã dùng với `delta` khác |
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

#### Sửa sản phẩm: bắt buộc gửi `version`

Giống sửa hồ sơ người dùng (xem *Sửa hồ sơ: bắt buộc gửi `version`*): `PATCH` phải kèm `version` mà client đã đọc. Thiếu thì trả 400. Version khác version hiện tại thì trả 409 `concurrent-modification` kèm `expectedVersion`, `currentVersion`, và không sửa gì.

```http
PATCH /api/products/1
Content-Type: application/json

{ "price": 179000, "version": 0 }
```

**`PATCH` không sửa được `stock`** (gửi lên → 400 với `errors.stock` chỉ sang `stock-adjustments`). Tồn kho có API riêng nhận số tăng / giảm (mục bên dưới), và `version` chỉ là của thông tin sản phẩm (sku, tên, giá, mô tả, category, `active`):

- **Đơn hàng không làm đổi `version`.** Admin mở form sửa giá, trong lúc đó có khách mua: admin lưu vẫn được (200), không bị 409 vô cớ dù sản phẩm đang bán chạy.
- **Không ai ghi đè được tồn kho bằng con số cũ.** Không client nào gửi con số tồn kho tuyệt đối nữa. Trong code, cột `stock` của entity `Product` là `updatable = false`, nên một `Product` nạp từ trước (còn giữ số tồn kho cũ) có bị sửa và lưu lại thì câu `UPDATE` của nó cũng không có cột `stock`.

Trước khi tách, `PATCH {"stock": 15, "version": 0}` của admin tính trên con số 10 đọc trước khi khách mua 3 cái sẽ ghi kho thành 15, mất 3 cái đã bán. Bắt buộc `version` thì chặn được (409), nhưng mỗi đơn đều tăng `version`, nên sửa tên hay giá của sản phẩm bán chạy cũng hay gặp 409. Tách tồn kho ra giải quyết cả hai.

Test: `orderDoesNotChangeVersion_adminEditReadBeforeOrderSucceeds`, `patchStock_returns400PointingToStockAdjustments`, `concurrentPatchesWithSameVersion_onlyOneWins`, `patch_withoutVersion_returns400` (`ProductApiIntegrationTests`); `staleEntitySaved_doesNotOverwriteStock` (`ProductModuleTests`).

#### Tự chạy với app thật: `scripts/product-lost-update.ps1`

Gọi API của app đang chạy, kiểm tra 3 case, mỗi case một sản phẩm mới: (1) admin sửa giá theo version đọc trước khi có đơn → 200, kho giữ đúng số sau đơn; `PATCH` có `stock` → 400; nhập hàng qua `stock-adjustments` → kho cộng đúng; (2) `-Concurrent` PATCH (mặc định 20) cùng gửi version 0, gửi cùng lúc → đúng 1 request 200; (3) PATCH không có `version` → 400.

```powershell
# cần app đang chạy (.\mvnw spring-boot:run)
powershell -ExecutionPolicy Bypass -File .\scripts\product-lost-update.ps1
```

```
== Case 1: admin sửa sản phẩm sau khi có đơn, theo version đọc trước khi có đơn
   tạo sản phẩm id 33705: version 0, stock 10, price 100000; người mua id 26803
   1. Admin GET          → version 0, stock 10, price 100000
   2. Khách POST /api/orders {"userId":26803,"items":[{"productId":33705,"quantity":3}]}
            → 201, đơn id 6962; sản phẩm lúc này: version 0, stock 7, price 100000
   3. Admin đổi giá, version đọc ở bước 1: PATCH {"price":90000,"version":0}
            → 200, version 1, stock 7, price 90000
   4. Admin sửa tồn kho bằng PATCH: {"stock":15,"version":1}
            → 400 validation: Dữ liệu gửi lên không hợp lệ [errors: stock = không sửa trực tiếp được, dùng POST /api/products/{id}/stock-adjustments]
   5. Admin nhập thêm 5 cái: POST /api/products/33705/stock-adjustments {"delta":5}
            → 200, version 1, stock 12, price 90000
   ĐÚNG: đơn hàng không làm admin bị 409; tồn kho chỉ đổi qua stock-adjustments, kho đúng 7 + 5 = 12
...
ĐÚNG (3/3 case): client gửi version cũ nhận 409, không ai ghi đè thay đổi của người khác
```

Mã thoát `0` nếu cả 3 case đều đúng; `1` nếu có case sai; `2` nếu không chạy được (app chưa chạy).

#### Điều chỉnh tồn kho: `POST /api/products/{id}/stock-adjustments`

Cộng (`delta > 0`, vd nhập hàng) hoặc trừ (`delta < 0`, vd hàng hỏng, kiểm kê thiếu) tồn kho. Gửi **số tăng / giảm**, không phải con số tồn kho mới, nên client không cần biết tồn kho hiện tại và không cần `version`.

```http
POST /api/products/1/stock-adjustments
Content-Type: application/json
Idempotency-Key: 6b1f0c2e-3d4a-4f5b-9c8d-7e6f5a4b3c2d

{ "delta": 5 }
```

→ **200** với sản phẩm sau khi điều chỉnh (`stock` mới; `version` không đổi).

```sql
UPDATE products SET stock = stock + :delta, updated_at = now()
WHERE id = :id AND stock + :delta >= 0
```

- **Một câu `UPDATE` tính trên con số mới nhất trong DB**, giống trừ kho khi đặt hàng. Nhập hàng, đặt hàng chạy cùng lúc thì các câu `UPDATE` lần lượt khoá dòng, câu sau cộng / trừ trên kết quả của câu trước: không mất lượt nào.
- **Trừ quá số đang có** (`stock + delta < 0`): câu `UPDATE` không cập nhật dòng nào → **409** `insufficient-stock` kèm `requested`, `available`; kho giữ nguyên.
- **Bắt buộc `Idempotency-Key`** như `POST /api/orders`. Cộng / trừ không tự an toàn khi gửi lại: client gửi `+5`, mạng chập chờn, client gửi lại thì sẽ thành `+10`. Có key thì lần gửi lại nhận nguyên văn response lần đầu (kèm `Idempotent-Replayed: true`), kho chỉ cộng một lần. Cùng key mà `delta` khác → 422 `idempotency-key-reused`.
- `delta` bắt buộc, khác 0 (`errors.deltaNonZero`), trong khoảng −1.000.000 … 1.000.000. Sản phẩm không tồn tại → 404. Sản phẩm ngừng bán vẫn điều chỉnh được.

60 request gửi cùng lúc (50 lượt nhập +1, 10 đơn mua 1 cái), kho ban đầu 10, với app thật:

| Cách cộng / trừ | Request thành công | Kho sau cùng |
|---|---|---|
| `stock = stock + :delta` (hiện tại) | 60/60 | **50** = 10 + 50 − 10 |
| Đọc tồn kho → cộng ở Java → `UPDATE ... SET stock = :mới` (thử nghiệm) | 60/60 | **11**: 39 lượt bị ghi đè, mà không request nào báo lỗi |

Test: `stockAdjustment_addsAndSubtracts`, `stockAdjustment_belowZero_returns409`, `stockAdjustment_sameKey_appliedOnce`, `stockAdjustment_invalidRequests`, `restocksAndOrdersAtOnce_loseNoUpdate` (`ProductApiIntegrationTests`).

#### Tự chạy với app thật: `scripts/stock-adjustment.ps1`

Kiểm tra 3 case, mỗi case một sản phẩm mới, kho ban đầu 10: (1) `-Restocks` lượt nhập +1 (mặc định 50) và `-Orders` đơn mua 1 cái (mặc định 10) gửi cùng lúc → mọi request thành công, kho = 10 + Restocks − Orders; (2) gửi cùng lượt nhập +5 ba lần với cùng `Idempotency-Key` → kho chỉ cộng 5 một lần, cùng key khác `delta` → 422; (3) trừ quá tồn kho → 409, kho giữ nguyên.

```powershell
# cần app đang chạy (.\mvnw spring-boot:run)
powershell -ExecutionPolicy Bypass -File .\scripts\stock-adjustment.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\stock-adjustment.ps1 -Restocks 200 -Orders 100
```

```
== Case 1: 50 lượt nhập +1 và 10 đơn mua 1 cái, gửi cùng lúc
   tạo sản phẩm id 33702, kho 10; người mua id 26802
   POST /api/products/33702/stock-adjustments {"delta":1} × 50   |   POST /api/orders (1 cái) × 10
   xong sau 583 ms
   nhập hàng 200: 50/50   đặt hàng 201: 10/10
   kho sau cùng: 50 (mong đợi 10 + 50 - 10 = 50)
   ĐÚNG: không mất lượt nào: kho = 10 + 50 - 10 = 50

== Case 2: gửi lại cùng một lượt nhập +5 với cùng Idempotency-Key
   tạo sản phẩm id 33703, kho 10; Idempotency-Key: adj-1791448732-retry
   lần 1 POST {"delta":5} → 200, stock 15, version 0
   lần 2 POST {"delta":5} → 200, stock 15, version 0   (Idempotent-Replayed: true)
   lần 3 POST {"delta":5} → 200, stock 15, version 0   (Idempotent-Replayed: true)
   cùng key, delta khác POST {"delta":6} → 422 idempotency-key-reused: Idempotency-Key 'adj-1791448732-retry' đã được dùng cho một request có nội dung khác
   kho sau cùng: 15 (mong đợi 10 + 5 = 15)
   ĐÚNG: gửi 3 lần nhưng kho chỉ cộng 5 một lần; lần 2, 3 nhận lại nguyên văn response lần đầu

== Case 3: trừ nhiều hơn số đang có
   tạo sản phẩm id 33704, kho 10
   POST /api/products/33704/stock-adjustments {"delta":-11}
            → 409 insufficient-stock: Sản phẩm ADJ-1791448732-3 không đủ hàng: cần 11, còn 10 (requested 11, available 10)
   kho sau cùng: 10
   ĐÚNG: bị từ chối với 409, kho giữ nguyên 10

ĐÚNG (3/3 case): điều chỉnh tồn kho không mất lượt nào, không cộng hai lần, không cho kho âm
```

Cùng script với bản thử nghiệm đọc → cộng ở Java → ghi:

```
   nhập hàng 200: 50/50   đặt hàng 201: 10/10
   kho sau cùng: 11 (mong đợi 10 + 50 - 10 = 50)
   SAI: kho là 11, không khớp 10 + 50 lượt nhập - 10 đơn = 50: có lượt cộng / trừ bị ghi đè (lost update)
```

Mã thoát `0` nếu cả 3 case đều đúng; `1` nếu có case sai; `2` nếu không chạy được (app chưa chạy).

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

- Email và username lưu chữ thường, nên trùng không phân biệt hoa/thường. Email và `fullName` được nhận kèm khoảng trắng đầu/cuối (vd copy-paste) và lưu sau khi bỏ khoảng trắng; email bỏ khoảng trắng trước khi kiểm tra định dạng, nên `" a@example.com "` hợp lệ. Username chỉ gồm `A-Z a-z 0-9 . _ -`, dài 3–50.
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

#### Tự chạy với app thật: `scripts/profile-lost-update.ps1`

Gọi API của app đang chạy, kiểm tra 3 case, mỗi case một người dùng mới:

1. **Client sửa dựa trên dữ liệu đã cũ:** An và Bình cùng `GET` (version 0). An đổi `phone`. Bình lưu form đang mở (tên mới, `phone` cũ, version 0) → mong đợi 409, số của An còn nguyên. Bình tải lại rồi sửa → 200.
2. **`-Concurrent` PATCH (mặc định 20) cùng gửi version 0, gửi cùng lúc** → mong đợi đúng 1 request 200, còn lại 409. Script đếm riêng 409 do bước so version và 409 do `@Version` khi ghi.
3. **PATCH không có `version`** → mong đợi 400 `validation`, hồ sơ giữ nguyên.

```powershell
# cần app đang chạy (.\mvnw spring-boot:run)
powershell -ExecutionPolicy Bypass -File .\scripts\profile-lost-update.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\profile-lost-update.ps1 -Concurrent 50
```

```
== Case 1: client sửa dựa trên dữ liệu đã cũ (lost update)
   tạo người dùng id 19902: version 0, fullName "Nguyễn Văn An", phone 0900000000
   1. An   GET   → version 0, fullName "Nguyễn Văn An", phone 0900000000
   2. Bình GET   → version 0, fullName "Nguyễn Văn An", phone 0900000000
   3. An   PATCH {"phone":"0911111111","version":0}
            → 200, version 1, fullName "Nguyễn Văn An", phone 0911111111
   4. Bình PATCH {"fullName":"Trần Thị Bình","phone":"0900000000","version":0}   (form cũ)
            → 409 concurrent-modification: Hồ sơ người dùng id = 19902 đã bị sửa sau lần bạn đọc (bạn gửi version 0, hiện tại là 1), hãy tải lại rồi sửa lại (expectedVersion 0, currentVersion 1)
   5. GET lại    → version 1, fullName "Nguyễn Văn An", phone 0911111111
   6. Bình tải lại rồi PATCH {"fullName":"Trần Thị Bình","phone":"0911111111","version":1}
            → 200, version 2, fullName "Trần Thị Bình", phone 0911111111
   ĐÚNG: thay đổi của An không bị ghi đè; Bình nhận 409, tải lại rồi sửa được

== Case 2: 20 PATCH cùng gửi version 0, gửi cùng lúc
   tạo người dùng id 19903: version 0, fullName "Tên gốc", phone 0900000000
   PATCH {"fullName":"Người <i>","version":0}, i = 1..20
   xong sau 58 ms
   200: 1 (Người 1)
   409 concurrent-modification: 19
        17 chặn ở bước so version (lúc đọc thì hồ sơ đã lên version mới)
         2 chặn bởi @Version khi ghi (đọc lúc còn version 0, có request khác ghi xen vào)
   hồ sơ sau cùng: version 1, fullName "Người 1", phone 0900000000
   ĐÚNG: chỉ Người 1 thành công, 19 request còn lại nhận 409, không ai bị ghi đè

== Case 3: PATCH không có version
   PATCH {"fullName":"Không gửi version"}
            → 400 validation: Dữ liệu gửi lên không hợp lệ [errors: version = must not be null]
   ĐÚNG: bị từ chối với 400, hồ sơ giữ nguyên

ĐÚNG (3/3 case): client gửi version cũ nhận 409, không ai ghi đè thay đổi của người khác
```

Mã thoát `0` nếu cả 3 case đều đúng; `1` nếu có case sai; `2` nếu không chạy được (app chưa chạy). Số request bị chặn ở mỗi lớp trong case 2 thay đổi theo từng lần chạy; chỉ cần tổng là `-Concurrent` − 1.

Chạy cùng script với bản app **trước khi** bắt buộc `version` (server bỏ qua `version` trong body):

```
   4. Bình PATCH {"fullName":"Trần Thị Bình","phone":"0900000000","version":0}   (form cũ)
            → 200, version 2, fullName "Trần Thị Bình", phone 0900000000
   SAI: Bình gửi version cũ mà vẫn 200: phone bị ghi đè về 0900000000, mất số An vừa đổi (lost update)
   ...
   200: 3 (Người 1, Người 12, Người 14)
   409 concurrent-modification: 17
         0 chặn ở bước so version (lúc đọc thì hồ sơ đã lên version mới)
        17 chặn bởi @Version khi ghi (đọc lúc còn version 0, có request khác ghi xen vào)
   SAI: 3 request cùng gửi version 0 đều thành công, mong đợi đúng 1: request sau ghi đè request trước
   ...
            → 200, version 4, fullName "Không gửi version", phone 0900000000
   SAI: mong đợi 400 validation có errors.version: không gửi version thì server không biết client đọc từ lúc nào

SAI: case 1, case 2, case 3 không như mong đợi
```

Chỉ có `@Version` thì vẫn có 17 request bị chặn, nhưng 3 request vẫn thắng: mỗi request đọc lại hồ sơ ở version mới nhất rồi mới sửa, nên request đọc sau khi một request khác đã commit vẫn ghi đè được.

#### Đăng ký trùng gửi cùng lúc

Đăng ký kiểm tra trùng theo hai lớp:

1. **Kiểm tra trước:** `existsByEmail`, `existsByAccountUsername`. Trùng thì trả 409 ngay, không tốn công băm mật khẩu.
2. **Unique constraint của DB:** `uk_users_email`, `uk_accounts_username`. `UserService` bắt lỗi vi phạm (`DbConstraints.isViolated`) và trả **cùng** 409 `duplicate-email` / `duplicate-username` như lớp 1, thay vì 409 `data-integrity` chung chung.

Chỉ có lớp 1 thì không đủ. Nhiều request gửi cùng lúc đều chạy `existsByEmail` trước khi request đầu tiên commit, nên cùng thấy "chưa có" và cùng INSERT. Có lớp 2 thì INSERT thứ hai phải chờ INSERT đầu tiên commit, rồi bị DB từ chối.

50 request đăng ký cùng email, gửi cùng lúc, với app thật:

| | `201` | `409 duplicate-email` | Người dùng có email này |
|---|---|---|---|
| Hiện tại (cả 2 lớp) | **1** | 49 (40 chặn ở lớp 1, 9 do DB chặn) | **1** |
| Bỏ `uk_users_email` (chỉ lớp 1) | **10** | 40 | **10** |
| Bỏ phần dịch lỗi DB (chỉ còn constraint) | 1 | 40; 9 request còn lại nhận `409 data-integrity` | 1 |
| Bỏ lớp 1 (chỉ còn constraint) | 1 | 49 | 1 |

- **Tạo trùng tối đa bằng số connection của pool** (Hikari mặc định 10), giống bản ngây thơ của *Test bán chớp nhoáng*: mỗi request giữ một connection suốt transaction, nên chỉ 10 request cùng qua được lớp 1 trước khi request đầu tiên commit.
- **Đảm bảo không trùng là nhờ constraint, không phải nhờ lớp 1.** Bỏ lớp 1, mọi test vẫn qua. Lớp 1 chỉ để trả lỗi sớm trong trường hợp thường gặp (đăng ký lại email đã có), không phải băm mật khẩu và không ghi lỗi vào log của PostgreSQL.
- Trùng username thì lỗi xảy ra ở `INSERT INTO accounts`, **sau khi** đã `INSERT INTO users`. Transaction rollback nên dòng `users` đó cũng mất, không để lại hồ sơ không có tài khoản.

Test: `duplicateEmailCaughtByDatabase_returnsDuplicateEmail` (ép đúng 1 request lọt qua lớp 1), `sameEmailAtOnce_registersExactlyOne`, `sameUsernameAtOnce_registersExactlyOne` (20 request cùng lúc).

#### Tự chạy với app thật: `scripts/duplicate-email.ps1`

Gửi cùng lúc `-Count` request đăng ký (mặc định 50) cùng một email, rồi `-Count` request cùng một username. Mong đợi mỗi case: đúng 1 request `201`, còn lại `409 duplicate-email` / `duplicate-username`.

```powershell
# cần app đang chạy (.\mvnw spring-boot:run)
powershell -ExecutionPolicy Bypass -File .\scripts\duplicate-email.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\duplicate-email.ps1 -Count 100
```

```
== Case 1: 50 request đăng ký cùng email dup1791447325@example.com, username khác nhau, gửi cùng lúc
   xong sau 358 ms
   201: 1
        Người 6 (id 20013, email dup1791447325@example.com, username dup1791447325-6)
   409 duplicate-email: 49   ví dụ: Email 'dup1791447325@example.com' đã được đăng ký
   ĐÚNG: chỉ 1 người dùng được tạo, 49 request còn lại nhận 409 duplicate-email

== Case 2: 50 request đăng ký cùng username dup1791447325, email khác nhau, gửi cùng lúc
   xong sau 470 ms
   201: 1
        Người 18 (id 20022, email dup1791447325-18@example.com, username dup1791447325)
   409 duplicate-username: 49   ví dụ: Username 'dup1791447325' đã có người dùng
   ĐÚNG: chỉ 1 người dùng được tạo, 49 request còn lại nhận 409 duplicate-username

ĐÚNG (2/2 case): đăng ký trùng gửi cùng lúc chỉ tạo đúng 1 người dùng, các request còn lại nhận 409
```

Mã thoát `0` nếu cả 2 case đều đúng; `1` nếu có case sai; `2` nếu không chạy được (app chưa chạy).

**Muốn thấy lỗi khi thiếu constraint** (chỉ làm trên DB thử nghiệm): bỏ `uk_users_email`, chạy script, rồi dọn dữ liệu trùng và thêm lại constraint.

```powershell
docker compose exec postgres psql -U shoplab -d shoplab -c 'ALTER TABLE users DROP CONSTRAINT uk_users_email'
powershell -ExecutionPolicy Bypass -File .\scripts\duplicate-email.ps1
```

```
== Case 1: 50 request đăng ký cùng email dup1791447327@example.com, username khác nhau, gửi cùng lúc
   xong sau 382 ms
   201: 10
        Người 1 (id 12, email dup1791447327@example.com, username dup1791447327-1)
        Người 2 (id 15, email dup1791447327@example.com, username dup1791447327-2)
        ...
   409 duplicate-email: 40   ví dụ: Email 'dup1791447327@example.com' đã được đăng ký
   SAI: 10 người dùng được tạo, mong đợi đúng 1
...
SAI: Case 1 không như mong đợi
```

```powershell
# giữ người dùng có id nhỏ nhất của mỗi email, xoá phần trùng, rồi thêm lại constraint
docker compose exec postgres psql -U shoplab -d shoplab `
  -c 'DELETE FROM accounts a USING users u WHERE a.user_id = u.id AND EXISTS (SELECT 1 FROM users o WHERE o.email = u.email AND o.id < u.id)' `
  -c 'DELETE FROM users u WHERE EXISTS (SELECT 1 FROM users o WHERE o.email = u.email AND o.id < u.id)' `
  -c 'ALTER TABLE users ADD CONSTRAINT uk_users_email UNIQUE (email)'
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
SET stock = stock - :quantity, updated_at = now()
WHERE id = :id AND active AND stock >= :quantity
RETURNING id, sku, name, price      -- chụp vào dòng đơn
```

- **Kiểm tra và trừ nằm trong cùng một câu**, nên không có khoảng hở giữa "đọc" và "ghi". Nhiều đơn cùng trừ một sản phẩm thì PostgreSQL cho các câu UPDATE lần lượt khoá dòng: câu đến sau chờ câu trước commit, rồi kiểm tra lại `stock >= :quantity` trên con số mới nhất.
- **Cập nhật 0 dòng** (không tồn tại, ngừng bán hoặc không đủ hàng) thì ném lỗi; transaction của đơn rollback, kể cả phần đã trừ của các sản phẩm trước trong cùng đơn, và không đơn nào được tạo. Lý do được đọc lại sau đó để trả đúng lỗi: `invalid-order` (422) hoặc `insufficient-stock` (409).
- Các sản phẩm của một đơn được trừ theo thứ tự `productId`, nên mọi đơn khoá các dòng theo cùng thứ tự → không deadlock.
- **Không tăng `version`**: `version` là của thông tin sản phẩm, nên đơn hàng không làm `PATCH` của admin bị 409. Câu UPDATE không đi qua entity, nên `Product` đã nạp trước đó trong cùng transaction vẫn giữ số tồn kho cũ; entity đó có bị sửa và lưu lại cũng không ghi đè được kho, vì cột `stock` là `updatable = false` (câu UPDATE của entity không có cột này).
- Đây là chỗ duy nhất quy tắc "không bán quá tồn kho" không nằm trong entity: nó nằm trong câu SQL, vì chỉ DB mới kiểm tra và trừ được trong cùng một bước. Ràng buộc `CHECK (stock >= 0)` của bảng là lớp chặn cuối.

Mỗi phần của câu lệnh đều có test giữ. Thử bỏ từng phần thì:

| Bỏ đi | Điều xảy ra (1.000 lượt mua, kho 1) | Test bắt được |
|---|---|---|
| Kiểm tra "đúng 1 dòng" | 1.000 × `201`: **1.000 đơn** cho 1 cái hàng | `FlashSaleIntegrationTests` |
| Điều kiện `stock >= :quantity` | CHECK của DB chặn kho âm, nhưng 999 người nhận `409 data-integrity` thay vì "hết hàng" | `FlashSaleIntegrationTests` |
| `updatable = false` của cột `stock` | `Product` nạp trước đó lưu lại được, ghi đè kho về số cũ | `ProductModuleTests` |

### So với cách "đọc → kiểm tra → trừ ở Java → lưu"

`NaiveStockDeductionTests` so sánh bản thật với một bản "ngây thơ" (`NaiveProductInventory`, chỉ có trong test): đọc tồn kho → kiểm tra còn hàng → trừ ở Java → lưu, không khoá gì. Kịch bản: 8 người cùng mua 1 cái, kho còn 5.

| Cách giữ hàng | Mua được | Lỗi | Kho còn | Vì sao |
|---|---|---|---|---|
| Ngây thơ, lưu bằng `UPDATE products SET stock = ?` | **8** | 0 | **4** | Cả 8 cùng đọc 5, cùng thấy còn hàng, cùng ghi 5 − 1 = 4: lần ghi sau đè lần ghi trước (lost update). Bán vượt 3 cái, mà kho vẫn báo còn 4 |
| Ngây thơ, lưu kèm kiểm tra version (optimistic lock) | 1 | 7 xung đột | 4 | `UPDATE products SET stock = ?, version = version + 1 WHERE id = ? AND version = ?`: người ghi đầu tiên đổi version, 7 người sau không khớp nên rollback. Không bán vượt, nhưng từ chối 7 người dù kho còn 4 |
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
- Phía Spring nhận `CannotAcquireLockException` (một `PessimisticLockingFailureException`). `GlobalExceptionHandler` đọc mã `40P01` và trả `409 deadlock` + `Retry-After`, tách riêng khỏi `409 lock-timeout`. Log ghi rõ mã lỗi: Hibernate ghi `HHH000247: ErrorCode: 0, SQLState: 40P01`, và `GlobalExceptionHandler` tự ghi `WARN ... Deadlock detected (SQLState 40P01): ERROR: deadlock detected` (có cả khi lỗi đi qua `JdbcClient`, nơi Hibernate không ghi gì). `GlobalExceptionHandlerTests` kiểm tra dòng log này.
- `lock_timeout` (5 giây, xem *Cấu hình đáng chú ý*) dài hơn `deadlock_timeout` (1 giây), nên deadlock luôn được phát hiện và báo là `deadlock` trước khi lượt chờ hết `lock_timeout`.
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

Qua API, `WalletApiIntegrationTests.oppositeTransfersAtOnce_noDeadlock` gửi **cùng lúc 100 lượt A→B 1 và 100 lượt B→A 2** (mỗi ví 1000): cả 200 lượt trả `200`, A = 1100, B = 900, và **tổng số dư mọi ví vẫn 2000** (test cộng bằng `SELECT sum(balance) FROM wallets`, `TransferDeadlockTests` cũng vậy). Thử đổi `WalletService` sang khoá theo thứ tự tham số: log ghi **87 lần** `Deadlock detected (SQLState 40P01)`, mỗi lần một lượt chờ ~1 giây, request xếp hàng chờ connection quá 30 giây và test đỏ.

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

### Bẫy @Transactional: gọi nội bộ (self-invocation)

Method `@Transactional` được gọi từ **một method khác trong cùng class** thì không có transaction nào được mở. `@Transactional` chạy nhờ **proxy**: Spring đưa cho nơi khác một object bọc ngoài service, proxy mở transaction rồi mới gọi vào method thật. `this.transfer(...)` là lời gọi từ bên trong object thật, không đi qua proxy:

```
bên ngoài ──► proxy ──(BEGIN)──► transfer()                         có transaction
bên ngoài ──► proxy ──► transferAll() ──this.transfer()──► transfer()   KHÔNG có transaction
```

`SelfInvocationTrapTests` tái hiện bằng `BatchTransferService` (chỉ có trong test): chuyển tiền hàng loạt (vd trả lương), mỗi lượt phải là một transaction riêng. `transferAll` gọi `this.transfer(...)`; `transfer` có `@Transactional`, trừ ví nguồn rồi `save`, sau đó mới tìm ví đích. Ví A có 100, chuyển hai lượt: A → ví không tồn tại 30 (lỗi ở bước tìm ví đích), rồi A → B 10.

| Cách gọi `transfer` | Có transaction? | Sau lượt lỗi (A → ví không tồn tại, 30) | Sau cả 2 lượt: A / B / tổng |
|---|---|---|---|
| `batch.transfer(...)` từ bên ngoài (qua proxy) | Có | Rollback, A vẫn 100 | (test chỉ chạy lượt lỗi) |
| `transferAll` → `this.transfer(...)` (**bẫy**) | **Không** | **A = 70**: phần trừ 30 đã commit | **60** / 110 / **170**: mất 30 |
| `transferAllEachInOwnTransaction`: mỗi lượt trong `TransactionTemplate` (**sửa**) | Có | Rollback, A vẫn 100 | 90 / 110 / **200** |

**Vì sao mất tiền mà không ai biết:** không có transaction thì mỗi lời gọi repository của Spring Data (`findById`, `save`) tự mở transaction riêng và **commit ngay**. Log `tx` của lượt lỗi khi gọi nội bộ, không có `BEGIN BatchTransferService.transfer` nào:

```
BEGIN    SimpleJpaRepository.findById (read-only)      ← tìm ví A
COMMIT   SimpleJpaRepository.findById
BEGIN    SimpleJpaRepository.save                      ← lưu ví A đã trừ 30
update wallets set balance=?, ... where id=? and version=?
COMMIT   SimpleJpaRepository.save                      ← đã commit, không rollback được nữa
BEGIN    SimpleJpaRepository.findById (read-only)      ← tìm ví đích: không có → ném lỗi
COMMIT   SimpleJpaRepository.findById
```

Gọi qua proxy (hoặc qua `TransactionTemplate`) thì cả lượt nằm trong một transaction, `save` chưa ghi gì xuống DB cho tới lúc commit:

```
BEGIN    BatchTransferService.transfer
select ... from wallets ...                             ← tìm ví A
select ... from wallets ...                             ← tìm ví đích: không có → ném lỗi
ROLLBACK BatchTransferService.transfer                  ← chưa có UPDATE nào, A giữ nguyên
```

**Code thật:** đã quét mọi class có `@Transactional` trong `src/main`, không chỗ nào gọi nội bộ một method `@Transactional`. Nếu `WalletService.transfer` bị gọi kiểu đó (test giả lập bằng cách gọi thẳng vào object thật bên trong proxy, `AopTestUtils.getTargetObject`), nó lỗi ngay `TransactionRequiredException: No active transaction` ở câu khoá `SELECT ... FOR UPDATE` (khoá dòng bắt buộc có transaction), trước khi đổi gì. Đó là may mắn, không phải được thiết kế để chặn: viết theo kiểu `findById` + `save` như `BatchTransferService` thì lặng lẽ mất tiền.

**Cách sửa**, từ hay dùng nhất:

1. **Tách method `@Transactional` sang bean khác** rồi gọi qua bean đó (vd `transferAll` ở một class, gọi `walletService.transfer(...)`): lời gọi đi qua proxy.
2. **Tự mở transaction bằng `TransactionTemplate`** cho từng lượt (cách test dùng): rõ ràng, không phụ thuộc proxy.
3. Tự inject chính mình (`@Lazy` + gọi `self.transfer(...)`) hoặc `AopContext.currentProxy()`: chạy được nhưng khó đọc, dễ bị người sau "dọn" mất.

Bẫy này áp dụng cho mọi annotation chạy nhờ proxy: `@Transactional`, `@Async`, `@Cacheable`, `@Retryable`...; method `private` thì không bao giờ qua proxy.

"Phá" để chắc test bắt được lỗi:

| Phá | Test fail |
|---|---|
| Bản sửa gọi thẳng `transfer(...)`, bỏ `TransactionTemplate` | `transferAllEachInOwnTransaction_keepsMoney`: A expected 90.00 but was **60.00** |
| Bỏ `@Transactional` trên `transfer` | `transfer_calledThroughProxy_rollsBack`: không còn proxy, lượt lỗi không rollback |

```bash
./mvnw test -Dtest=SelfInvocationTrapTests
```

#### Tự chạy: `scripts/self-invocation-trap.ps1`

Bản lỗi chỉ có trong test, app không có API nào đi vào nó, nên script **chạy test** `SelfInvocationTrapTests` qua Maven (cần Docker đang chạy, như khi chạy test), rồi đọc log `BEGIN` / `COMMIT` / `ROLLBACK`, SQL và các dòng `[self-invocation]` mà test ghi ra, in diễn biến từng lượt chuyển của 4 case theo thứ tự: qua proxy → bẫy → sửa → code thật.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\self-invocation-trap.ps1          # 4 case, mong đợi ĐÚNG
powershell -ExecutionPolicy Bypass -File .\scripts\self-invocation-trap.ps1 -Break   # tự "phá" bản sửa, xem test bắt được
```

```
-- 2. BẪY: transferAll gọi this.transfer(...) (không qua proxy)
   mong đợi: KHÔNG có transaction; lượt lỗi vẫn trừ 30 của A → mất 30 (A 60, B 110, tổng 170)
   lượt: ví A → ví không tồn tại, 30.00   (trong transfer có transaction: KHÔNG)
     09:42:20.524  BEGIN SimpleJpaRepository.findById (read-only)                tìm ví A
     09:42:20.579  COMMIT SimpleJpaRepository.findById
     09:42:20.581  BEGIN SimpleJpaRepository.save                                lưu ví A đã trừ 30.00
     09:42:20.619    update wallets set balance=? ...
     09:42:20.628  COMMIT SimpleJpaRepository.save                               ← commit ngay, không rollback được nữa
     09:42:20.629  BEGIN SimpleJpaRepository.findById (read-only)                tìm ví không tồn tại: không có → lỗi
     09:42:20.642  COMMIT SimpleJpaRepository.findById
   lượt: ví A → ví B, 10.00   (trong transfer có transaction: KHÔNG)
     ...
   kết quả: [FAILED,TRANSFERRED]; transaction trong transfer: KHÔNG; A = 60.00, B = 110.00, tổng 170.00 → MẤT 30.00
   đúng như mong đợi

-- 3. SỬA: mỗi lượt chạy trong transaction mở bằng TransactionTemplate
   mong đợi: có transaction; lượt lỗi rollback, lượt kia vẫn chuyển (A 90, B 110, tổng 200)
   lượt: ví A → ví không tồn tại, 30.00   (trong transfer có transaction: CÓ)
     09:42:20.804  BEGIN BatchTransferService.transfer (TransactionTemplate)     ← mở transaction cho cả lượt
     09:42:20.805    select ... from wallets                                     tìm ví A
     09:42:20.808    select ... from wallets                                     tìm ví không tồn tại: không có → lỗi
     09:42:20.809  ROLLBACK BatchTransferService.transfer (TransactionTemplate)  ← huỷ cả lượt: chưa có UPDATE nào
   ...
   kết quả: [FAILED,TRANSFERRED]; transaction trong transfer: CÓ; A = 90.00, B = 110.00, tổng 200.00
   đúng như mong đợi
...
ĐÚNG (4/4 case): gọi nội bộ this.transfer(...) không có transaction và làm mất 30;
      qua proxy hoặc TransactionTemplate thì lượt lỗi rollback, tổng tiền giữ 200
```

`-Break` truyền `-Dshoplab.trap.break=true` cho test: `transferAllEachInOwnTransaction` bỏ `TransactionTemplate`, gọi thẳng `transfer(...)` như bản bẫy. Case 3 lúc đó ra `MẤT 30.00`, test đỏ ở `expected: 90.00 but was: 60.00`, và script kết luận `ĐÚNG: đã phá bản sửa và test bắt được`. Không cần sửa code, chạy lại không có `-Break` là về như cũ.

Mã thoát `0` nếu đúng như mong đợi (không `-Break`: 4 test xanh; `-Break`: đúng test của bản sửa đỏ); `1` nếu không; `2` nếu không chạy được test (vd Docker chưa chạy). Trên macOS / Linux: `pwsh ./scripts/self-invocation-trap.ps1`.

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
| `insufficient-stock` | 409 | Không đủ hàng (đặt hàng, hoặc trừ tồn kho quá số đang có) |
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
| `common` | Phần dùng chung (shared kernel) | `ApiException`, `BaseEntity`, `AuditedEntity`, `DbConstraints`, `StaleVersionException`, `ValidationPatterns` | (không module nào) |
| `product` | Danh mục sản phẩm, tồn kho (giữ hàng cho đơn, điều chỉnh tồn kho) | `ProductInventory` (giữ hàng), `ReservedItem`, `ProductUnavailableException`, `InsufficientStockException`; `ProductReferences` (module khác cài đặt) | `common`, `idempotency` |
| `idempotency` | Chạy request ghi đúng một lần theo `Idempotency-Key` | `IdempotencyService` và các exception của nó | `common` |
| `order` | Đơn hàng | (chưa có) | `common`, `product`, `user`, `idempotency` |
| `user` | Người dùng: hồ sơ + tài khoản đăng nhập | `UserDirectory` (tra người dùng đang ACTIVE), `UserSummary`, `UserUnavailableException` | `common` |
| `wallet` | Ví tiền của người dùng (số dư không âm): tạo ví, nạp tiền, chuyển tiền giữa hai ví | (chưa có) | `common`, `user`, `idempotency` |

```
order   ──► product, user, idempotency
wallet  ──► user, idempotency
product ──► idempotency
mọi module ──► common
```

Phụ thuộc chỉ đi một chiều: `product`, `user` không phụ thuộc module nghiệp vụ nào khác (`product` chỉ dùng thêm `idempotency`, module hạ tầng); `idempotency` chỉ phụ thuộc `common`.

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
├── common/         ApiException, BaseEntity, AuditedEntity, DbConstraints, StaleVersionException, ValidationPatterns
│   ├── config/     JpaAuditingConfig, SchedulingConfig, TransactionLogging
│   └── web/        GlobalExceptionHandler
├── product/        ProductInventory, ReservedItem, ProductUnavailableException, InsufficientStockException,
│   │               ProductReferences
│   ├── internal/   Product, ProductRepository, ProductService, DefaultProductInventory,
│   │               CreateProductCommand, UpdateProductCommand, AdjustStockCommand, các exception nội bộ
│   └── web/        ProductController, CreateProductRequest, PatchProductRequest, StockAdjustmentRequest,
│                   ProductResponse
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
    │                   không deadlock khi khoá theo id); BatchTransferService + SelfInvocationTrapTests
    │                   (bẫy gọi nội bộ @Transactional)
    └── web/            WalletApiIntegrationTests
```