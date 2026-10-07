# ShopLab

REST API bán hàng tối giản dùng để luyện thiết kế API: CRUD sản phẩm, đặt hàng có **Idempotency-Key**, trả mọi lỗi theo chuẩn **ProblemDetail (RFC 9457)**.

**Công nghệ:** Java 21 · Spring Boot 4 (Web MVC, Data JPA, Validation) · PostgreSQL 17 · Flyway · Testcontainers

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
./mvnw test -Dtest=ArchitectureTests                  # riêng test kiến trúc
```

Test đặt theo package của từng module, gồm 4 loại:

| Loại | Ví dụ | Dựng gì |
|---|---|---|
| Unit test | `ProductTest`, `OrderTest`, `CreateOrderCommandTest`, `RequestFingerprintTest` | Không Spring, không DB |
| Test slice | `ProductRepositoryTests` (`@DataJpaTest`), `GlobalExceptionHandlerTests` (`@WebMvcTest`) | Chỉ một tầng |
| Integration test | `OrderIdempotencyIntegrationTests`, `OrderApiIntegrationTests`, `ProductApiIntegrationTests` | App thật trên cổng ngẫu nhiên + PostgreSQL thật (Testcontainers), gồm cả kịch bản đồng thời và rollback |
| Test kiến trúc | `ArchitectureTests` (ArchUnit) | Đọc bytecode, kiểm tra quy tắc giữa các module |

### Test thủ công

- **Postman:** import `shoplab.postman_collection.json` → *Run collection* (chạy đúng thứ tự). Biến `baseUrl` mặc định `http://localhost:8080`.
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

Ví dụ:

```http
POST /api/orders
Content-Type: application/json
Idempotency-Key: 3f1c2a9e-7b4d-4c1e-9a55-0d2f6b8e1a77

{
  "customerName": "Nguyễn Văn A",
  "customerEmail": "a.nguyen@example.com",
  "items": [{ "productId": 1, "quantity": 2 }]
}
```

**Hành vi của `POST /api/orders`:**

| Tình huống | Mã | Ghi chú |
|---|---|---|
| Lần đầu với key mới | **201** | Tạo đơn, trừ kho |
| Gửi lại cùng key + cùng nội dung | **201** | Trả **nguyên văn** response lần đầu, header `Idempotent-Replayed: true`, không tạo đơn mới |
| Thiếu header `Idempotency-Key` | **400** | |
| Key sai định dạng | **400** | 8–255 ký tự `[A-Za-z0-9_-]`, khuyến nghị UUID |
| Body không hợp lệ (email sai, `items` rỗng…) | **400** | Kèm `errors` theo từng trường |
| Cùng key nhưng nội dung khác | **422** | `idempotency-key-reused` |
| Sản phẩm không tồn tại / ngừng bán | **422** | `invalid-order` |
| Không đủ hàng | **409** | Kèm `sku`, `requested`, `available` |
| Request khác cùng key đang chạy quá 5 giây | **409** | Header `Retry-After: 1`, gửi lại với **cùng** key |

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

`request_hash` là SHA-256 của JSON dạng chuẩn hoá của request (`CreateOrderCommand`: tên, email chữ thường, các dòng đã gộp và sắp theo `productId`). Băm JSON chứ không tự ghép chuỗi, nên hai request khác nhau không thể trùng mã băm.

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
    "customerEmail": "must be a well-formed email address",
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
| `duplicate-sku` | 409 | SKU đã tồn tại |
| `insufficient-stock` | 409 | Không đủ hàng |
| `concurrent-modification` | 409 | Bản ghi vừa bị request khác sửa (`@Version`) |
| `data-integrity` | 409 | Vi phạm ràng buộc DB (vd xoá sản phẩm đã có trong đơn) |
| `lock-timeout` / `idempotency-in-progress` | 409 | Đang có request khác xử lý cùng dữ liệu, kèm `Retry-After` |
| `idempotency-key-reused` | 422 | Key đã dùng với nội dung khác |
| `invalid-order` | 422 | Đơn hàng không xử lý được (sản phẩm không tồn tại / ngừng bán) |
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

## 4. Cấu trúc thư mục

```
src/main/java/com/shoplab/
├── product/        Product, CreateProductCommand, UpdateProductCommand, ReservedItem,
│                   ProductController, ProductService, ProductRepository, dto/
├── order/          Order, OrderItem, CreateOrderCommand, OrderController, OrderService, OrderRepository, dto/
├── idempotency/    IdempotencyService, IdempotencyStore, IdempotencyStatus, RequestFingerprint, IdempotencyCleanupJob
└── common/         BaseEntity, AuditedEntity, ApiException, GlobalExceptionHandler, DbConstraints,
                    JpaAuditingConfig, SchedulingConfig
src/main/resources/db/migration/
├── V1__init.sql                              products, orders, order_items
├── V2__add_product_category.sql              cột category
├── V3__create_idempotency_keys.sql           bảng idempotency_keys
├── V4__idempotency_store_response_body.sql   lưu nội dung phản hồi (JSONB)
├── V5__create_users_and_accounts.sql         bảng users, accounts
├── V6__order_items_product_snapshot.sql      chụp sku, tên sản phẩm vào dòng đơn
├── V7__idempotency_store_raw_response.sql    lưu nguyên văn response (body TEXT + header)
└── V8__ids_from_sequence.sql                 id lấy từ sequence (bước 50) thay vì IDENTITY
src/test/java/com/shoplab/
├── TestcontainersConfiguration.java          PostgreSQL 17 (cùng bản với docker-compose)
├── TestShoplabApplication.java
├── IntegrationTestBase.java                  nền chung cho integration test qua HTTP
├── ArchitectureTests.java                    quy tắc giữa các module (ArchUnit)
├── common/         GlobalExceptionHandlerTests
├── idempotency/    RequestFingerprintTest
├── order/          OrderTest, CreateOrderCommandTest, OrderApiIntegrationTests, OrderIdempotencyIntegrationTests
└── product/        ProductTest, ProductRepositoryTests, ProductApiIntegrationTests
```

### Quy tắc giữa các module

`ArchitectureTests` kiểm tra tự động các quy tắc dưới đây; vi phạm thì test đỏ và chỉ rõ chỗ vi phạm.

- Không có vòng phụ thuộc giữa các module. `common` không phụ thuộc module nào; `idempotency` không biết tới `product` hay `order`.
- Module chỉ dùng phần public của module khác. Repository và các hàm thay đổi dữ liệu của entity để package-private, nên chỉ code trong cùng package mới gọi được. Entity và DTO web không được dùng ngoài module của nó.
- `order` giữ hàng qua `ProductService.reserveStock(...)`: module product khoá, kiểm tra và trừ kho ngay trong transaction của đơn. Dòng đơn tham chiếu sản phẩm bằng `productId` và chụp lại `sku`, tên, giá tại thời điểm đặt, nên sửa sản phẩm không làm đổi đơn cũ.
- Entity tự chuẩn hoá và tự kiểm tra dữ liệu của mình (`Product`, `Order`); DTO web chỉ mang dữ liệu và validation.
- Phân tầng trong module: controller đổi DTO web ↔ command / entity; service nhận command (`CreateProductCommand`, `UpdateProductCommand`, `CreateOrderCommand`) và trả entity, không biết gì về HTTP. Chỉ controller (và chính các DTO) được dùng package `dto`.
- Entity kế thừa `common.BaseEntity` (id lấy từ sequence của bảng, khai báo bằng `@SequenceGenerator` trên class) hoặc `common.AuditedEntity` (thêm `version`, `createdAt`, `updatedAt` do Spring Data auditing điền).
- Lỗi nghiệp vụ kế thừa `common.ApiException` (tự mang status, `type`, `title`). `GlobalExceptionHandler` không import exception của module nào.
- Lỗi ràng buộc DB do service sở hữu dữ liệu tự dịch theo tên constraint, ví dụ `uk_products_sku` → `duplicate-sku`.