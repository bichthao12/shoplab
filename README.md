# ShopLab

REST API bán hàng tối giản dùng để luyện thiết kế API: CRUD sản phẩm, đặt hàng có **Idempotency-Key**, đăng ký người dùng (mật khẩu băm BCrypt), trả mọi lỗi theo chuẩn **ProblemDetail (RFC 9457)**.

**Công nghệ:** Java 21 · Spring Boot 4 (Web MVC, Data JPA, Validation) · Spring Modulith · Spring Security Crypto (chỉ BCrypt) · PostgreSQL 17 · Flyway · Testcontainers

**Ghi chú đồng thời:** [`docs/concurrency-notes.md`](docs/concurrency-notes.md): bảng mức cô lập × hiện tượng (lập bằng thí nghiệm `IsolationLevelTests`), 5 ý tóm tắt, postmortem cho 12 kịch bản đã "phá", trả lời 3 câu hỏi về transaction, `FOR UPDATE` và deadlock.

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
| `powershell -ExecutionPolicy Bypass -File .\scripts\clear-data.ps1` | Xoá **dữ liệu** của mọi bảng, giữ các bảng và lịch sử migration (xem bên dưới) |

**Xoá dữ liệu, giữ bảng: `scripts/clear-data.ps1`.** Script `TRUNCATE` mọi bảng trong schema `public` bằng một câu lệnh, trừ `flyway_schema_history`, nên app vẫn khởi động bình thường mà không chạy lại migration nào. Bảng thêm bằng migration sau này cũng tự được tính vào. Trước khi xoá, script in số dòng của từng bảng rồi hỏi lại; `-Force` để bỏ qua câu hỏi. Đã thử: xoá 12,1 triệu dòng (bộ dữ liệu lớn) mất 0,3 giây.
- **Mặc định giữ nguyên sequence:** id mới tiếp tục từ số cũ, chạy lúc app đang chạy cũng được.
- **`-ResetIds`:** đặt lại mọi sequence (`TRUNCATE ... RESTART IDENTITY`), id mới lại bắt đầu từ 1 (đã thử: người dùng đăng ký đầu tiên sau đó có id 1). Cần **tắt app** trước (script kiểm tra), vì app đang giữ sẵn một dải id lấy trước từ sequence.
- Khác `docker compose down -v`: lệnh đó xoá cả volume, tức cả bảng, lịch sử migration và extension `pg_stat_statements`; lần chạy app sau Flyway phải tạo lại từ đầu.
- Không có PowerShell:
  ```bash
  docker exec shoplab-postgres psql -U shoplab -d shoplab -c "TRUNCATE accounts, idempotency_keys, order_items, orders, products, users, wallets"
  ```

### Chạy app không cần Docker Compose

Chạy class `TestShoplabApplication` (trong `src/test/java`): app tự dựng PostgreSQL bằng Testcontainers, không cần cấu hình datasource.

### Dữ liệu lớn: 1 triệu user, 10 triệu đơn (`scripts/seed-big-data.ps1`)

Cho thí nghiệm index: bảng phải đủ lớn thì `EXPLAIN ANALYZE` mới thấy khác biệt giữa có và không có index. Dữ liệu sinh thẳng trong PostgreSQL bằng `generate_series` (không gọi API, không chạy test); toàn bộ cách sinh nằm trong `scripts/seed-big-data.sql`, script PowerShell chỉ kiểm tra điều kiện rồi chạy file đó bằng psql trong container.

**1. Cấp cho Docker ít nhất 4 GB RAM**

- Docker Desktop trên Windows chạy bằng WSL 2 (mặc định): RAM do WSL quyết định (mặc định một nửa RAM máy). Muốn đặt cố định, tạo `%UserProfile%\.wslconfig`:
  ```ini
  [wsl2]
  memory=4GB
  ```
  rồi chạy `wsl --shutdown` và mở lại Docker Desktop.
- Docker Desktop dùng Hyper-V, hoặc macOS: *Settings → Resources → Memory*.
- Kiểm tra: `docker info --format "{{.MemTotal}}"`. Cấp 4 GB thì lệnh báo khoảng 3,8 GB (kernel của máy ảo giữ một phần), nên script chỉ dừng khi dưới 3,5 GB (`-SkipMemoryCheck` để bỏ qua khi sinh ít).

Vì sao 4 GB: bảng `orders` 1,3 GB cộng 4 index 0,9 GB; PostgreSQL trong compose giữ `shared_buffers=1GB`; lúc nạp, bước gắn tên người đặt vào 10 triệu đơn và xếp theo id dùng thêm vài trăm MB. Chạy thử trong container giới hạn 4 GB: bộ nhớ container (tính cả page cache) cao nhất 2,3 GB khi sinh, 2,8 GB khi chạy thêm một phép join 10 triệu × 10 triệu dòng.

**2. Chạy**

```powershell
docker compose up -d     # docker-compose.yml vừa đổi (shared_buffers, shm_size): lệnh này tạo lại container, dữ liệu giữ nguyên
.\mvnw spring-boot:run   # chạy một lần để Flyway tạo bảng, khởi động xong thì Ctrl+C
powershell -ExecutionPolicy Bypass -File .\scripts\seed-big-data.ps1
```

- **Xoá sạch** `users`, `accounts`, `wallets`, `orders`, `order_items`, `idempotency_keys` rồi sinh lại (giữ `products`). DB đang có dữ liệu thì script hỏi lại, `-Force` để bỏ qua.
- **App phải tắt** trong lúc sinh (script và file .sql đều kiểm tra): Hibernate giữ sẵn dải 50 id lấy trước từ sequence. App chạy trong lúc sinh thì dải đó trùng id vừa sinh, lần đăng ký tiếp theo trả 409 vì trùng khoá chính (đã thử). Sinh xong, chạy lại app thì app lấy dải mới, sau id lớn nhất.
- **Một transaction:** lỗi giữa chừng thì rollback hết, DB như cũ, kể cả các index đã tạm bỏ (đã thử: ép lỗi ở bước sinh đơn, và để psql khác giữ khoá bảng `orders` cho tới khi hết `lock_timeout` 10 giây).
- Mất khoảng 2 phút (máy 4 CPU: 2 phút 08 giây), cần khoảng 5 GB đĩa trống cho Docker. Thử nhanh: `-Users 10000 -Orders 100000 -SkipMemoryCheck` (2 giây).
- Không có PowerShell (macOS, Linux):
  ```bash
  docker cp scripts/seed-big-data.sql shoplab-postgres:/tmp/
  docker exec shoplab-postgres psql -U shoplab -d shoplab -f /tmp/seed-big-data.sql   # thêm -v users=10000 -v orders=100000 để thử nhỏ
  ```

**3. Dữ liệu sinh ra** (số đo của lần chạy trên)

| Bảng | Nội dung |
|---|---|
| `users` 1.000.000 | Đăng ký rải đều trong 3 năm gần nhất, id tăng theo thời gian. Tên Việt theo tỉ lệ họ thật (Nguyễn 38%, Trần 11%, Lê 9,6%...), nam nữ mỗi bên một nửa. Email chữ thường `<tên>.<họ><id>@gmail.com` (75%), yahoo.com, outlook.com...; 70% có số điện thoại |
| `accounts` 1.000.000 | Mỗi user một tài khoản, username = phần trước `@` của email, mật khẩu chung `shoplab-seed` (BCrypt); 97% ACTIVE, 2% LOCKED, 1% DISABLED |
| `orders` 10.000.000 | Ngày đặt rải đều trong 2 năm gần nhất: mỗi ngày 13.697–13.700 đơn. id tăng theo ngày đặt như đơn thật. Người đặt là user đã đăng ký trước ngày đặt, user lâu năm đặt nhiều hơn: trung vị 9 đơn mỗi user, p99 46, nhiều nhất 1.427, 8,9% user chưa có đơn. Tên, email trên đơn chụp từ hồ sơ như app làm. Đơn không có dòng hàng (`order_items`) |

Trạng thái theo tuổi đơn như một shop thật: chỉ đơn mới vài ngày còn PAID / SHIPPED; đơn cũ đã COMPLETED hoặc CANCELLED, trừ một ít đơn treo PENDING (chưa thanh toán, không ai huỷ).

| Tuổi đơn | PENDING | PAID | SHIPPED | COMPLETED | CANCELLED |
|---|---|---|---|---|---|
| dưới 1 ngày | 40% | 40% | 15% | | 5% |
| 1–7 ngày | 3% | 10% | 50% | 30% | 7% |
| trên 7 ngày | 2% | | | 90% | 8% |
| **Cả bảng (đo được)** | **2,06%** (206.028) | 0,14% (13.567) | 0,43% (43.057) | 89,39% | 7,99% |

Kích thước: `orders` 1.284 MB, index `idx_orders_user_id_created_at` 387 MB, `orders_pkey` và `idx_orders_created_at` mỗi cái 214 MB, `idx_orders_status` 67 MB; `users` 112 MB, `accounts` 159 MB.

Nên biết khi đọc `EXPLAIN` trên bộ dữ liệu này:
- `orders` nằm trên đĩa đúng theo thứ tự `created_at` (tương quan 1,0 trong `pg_stats`), còn `user_id` gần như rải ngẫu nhiên (0,28): đọc đơn trong một khoảng ngày chạm ít trang của bảng, đọc đơn của một user thì gần như mỗi đơn một trang.

**4. VACUUM ANALYZE sau khi nạp**

Bước cuối của script, `VACUUM (FREEZE, ANALYZE) users, accounts, orders` chạy sau COMMIT, chuẩn bị đủ cho mọi thí nghiệm `EXPLAIN` sau đó:
- **ANALYZE** ghi thống kê từng cột vào `pg_stats` (giá trị phổ biến kèm tần suất, histogram). Không có thì planner đoán `status = '...'` khớp 0,5% số dòng, dù là PENDING (2%) hay COMPLETED (89%).
- **VACUUM** đánh dấu trang all-visible trong visibility map. Index Only Scan chỉ bỏ qua bảng ở những trang đã đánh dấu; trang chưa đánh dấu thì vẫn phải ghé bảng xem dòng còn hợp lệ không (`Heap Fetches`).
- **FREEZE** đánh dấu sẵn mọi dòng vừa nạp: câu SELECT đầu tiên không phải tự ghi hint bit vào cả bảng, sau này cũng không có lượt VACUUM chống wraparound nào ghi lại cả bảng giữa lúc đang đo.

Ngay sau bước này script in bảng kiểm tra (số cột có thống kê, % trang all-visible của từng bảng) và một `EXPLAIN` mong đợi `Index Only Scan ... Heap Fetches: 0`.

Đo trước và sau trên cùng 10 triệu đơn: `scripts/vacuum-analyze-lab.sql` chép `orders` sang schema `vacuum_lab` (tắt autovacuum trên bản chép), đo hai câu `SELECT count(*) FROM orders WHERE status = ...` sau từng lệnh, rồi xoá schema. Không đụng bảng thật, chạy khoảng 30 giây:

```powershell
docker cp scripts/vacuum-analyze-lab.sql shoplab-postgres:/tmp/
docker exec shoplab-postgres psql -U shoplab -d shoplab -f /tmp/vacuum-analyze-lab.sql
```

| Bước | Câu đếm | Cách đọc | Ước tính | Thực tế | Heap Fetches | Trang đọc | Thời gian |
|---|---|---|---|---|---|---|---|
| Chưa ANALYZE, chưa VACUUM | PENDING | Bitmap Heap Scan | 50.000 | 205.590 | | 116.780 | 609 ms |
| | COMPLETED | Bitmap Heap Scan | 50.000 | 8.938.929 | | 171.743 | 2.270 ms |
| Sau ANALYZE | PENDING | Index Only Scan | 198.331 | 205.590 | **205.590** | 116.780 | 188 ms |
| | COMPLETED | Seq Scan | 8.959.223 | 8.938.929 | | 164.352 | 1.740 ms |
| Sau VACUUM | PENDING | Index Only Scan | 198.333 | 205.590 | **0** | **182** | **21 ms** |
| | COMPLETED | Index Only Scan | 8.959.333 | 8.938.929 | 0 | 7.649 | 952 ms |

Thí nghiệm tắt truy vấn song song để số dòng ước tính / thực tế là của cả câu; mỗi câu chạy một lần trước khi đo để dữ liệu đã nằm trong bộ nhớ.
- **ANALYZE sửa ước tính:** từ 50.000 (đoán 0,5%) thành 198 nghìn và 8,96 triệu, sát thực tế. Với COMPLETED, planner thôi đi qua index (Bitmap Heap Scan đọc index rồi vẫn đọc cả bảng, 2,3 giây) mà đọc thẳng cả bảng (1,7 giây).
- **VACUUM mở đường cho Index Only Scan:** trước VACUUM, Index Only Scan vẫn ghé bảng cho cả 205.590 dòng, chạm 116.780 trang, tức 71% bảng vì đơn PENDING rải khắp bảng. Sau VACUUM không ghé lần nào, chỉ đọc 182 trang index: nhanh gấp 9 lần.
- **Câu đọc đầu tiên sau khi nạp tự ghi:** trên bản chép vừa tạo, `SELECT count(*)` lần đầu sửa 162.304 / 164.352 trang (ghi hint bit) và mất 2,4 giây; lần hai không sửa trang nào, 1,4 giây. Lúc đó planner còn đoán bảng có 986 nghìn dòng (chưa có thống kê, đoán theo số trang).

**Bẫy: transaction mở từ trước khi nạp xong.** VACUUM chỉ đánh dấu all-visible những dòng mà mọi transaction đang chạy đều thấy. Một psql hay công cụ DB đang `BEGIN` dở từ trước lúc script COMMIT thì không thấy dữ liệu mới, nên VACUUM không đánh dấu được trang nào. Đã thử: 0% trang all-visible, planner bỏ hẳn Index Only Scan (dùng Bitmap Heap Scan). Script phát hiện trường hợp này và in cảnh báo kèm phiên đang giữ transaction; đóng phiên đó rồi chạy `VACUUM users, accounts, orders;` là đủ (đã thử: lên 100%, `Heap Fetches: 0`).

Autovacuum rồi cũng tự ANALYZE / VACUUM sau khi nạp nhiều dòng, nhưng không biết lúc nào: nếu nó chạy giữa lúc đang đo thì plan và thời gian đổi giữa chừng. Vì vậy script chạy tay ngay sau khi nạp.

### Câu SQL tốn thời gian nhất: `pg_stat_statements` (`scripts/top-queries.ps1`)

`pg_stat_statements` cộng dồn cho từng câu SQL: số lần chạy, tổng / trung bình / lâu nhất, số trang đọc. Các câu chỉ khác tham số được gộp thành một dòng (giá trị thay bằng `$1`, `$2`...). Sắp theo **tổng** thời gian là tìm câu đáng sửa nhất: một câu 0,1 ms chạy 28 nghìn lần tốn hơn một câu 30 ms chạy 10 lần.

**Bật:** `docker-compose.yml` thêm `shared_preload_libraries=pg_stat_statements` (thư viện chỉ nạp được lúc PostgreSQL khởi động) và `track_io_timing=on` (thêm thời gian chờ đọc đĩa của từng câu). Script tự chạy `CREATE EXTENSION IF NOT EXISTS pg_stat_statements` trong DB `shoplab`. Extension không nằm trong migration: nó là công cụ theo dõi, không phải schema của app, và cần quyền superuser.

Lỗi `pg_stat_statements must be loaded via "shared_preload_libraries"`: container vẫn chạy theo `docker-compose.yml` cũ (vd chạy `docker compose up -d` trước khi `git pull`, hoặc bật lại container từ Docker Desktop). Kiểm tra bằng `docker inspect shoplab-postgres --format "{{json .Config.Cmd}}"`: không có `shared_preload_libraries=pg_stat_statements` thì chạy `git pull` rồi `docker compose up -d --force-recreate`. Extension đã tạo trước đó không cần tạo lại.

```powershell
git pull origin master
docker compose up -d --force-recreate     # tạo lại container theo cấu hình mới, dữ liệu giữ nguyên
docker exec shoplab-postgres psql -U shoplab -d shoplab -c "SHOW shared_preload_libraries"   # phải ra pg_stat_statements
.\mvnw spring-boot:run "-Dspring-boot.run.arguments=--logging.level.sql=INFO --logging.level.tx=INFO"   # tắt log SQL cho app không chậm vì ghi log
powershell -ExecutionPolicy Bypass -File .\scripts\top-queries.ps1     # mặc định -Minutes 3 -Parallel 16 -Top 3
```

Script gọi API thật (không chạy test):
1. Lấy mẫu 2.000 user đang ACTIVE và 2.000 đơn có sẵn (đọc thẳng DB), tạo 50 sản phẩm tồn kho lớn qua API.
2. `pg_stat_statements_reset()`: từ đây chỉ tính các câu chạy trong lúc gọi API.
3. Gọi API trong `-Minutes` phút, lúc nào cũng có `-Parallel` request đang chạy, chọn ngẫu nhiên theo tỉ lệ: 35% `GET /api/orders?userId=` (đơn của tôi), 20% `GET /api/orders/{id}`, 15% `GET /api/users/{id}`, 10% `GET /api/products?category=`, 15% `POST /api/orders` (1–3 sản phẩm), 5% `POST /api/users`.
4. In số request, lỗi, thời gian phản hồi của từng loại, rồi `-Top` câu có tổng thời gian chạy lớn nhất. Phần báo cáo nằm ở `scripts/top-queries.sql`, chạy riêng được (vd sau khi tự gọi API bằng Postman):
   ```powershell
   docker cp scripts/top-queries.sql shoplab-postgres:/tmp/
   docker exec shoplab-postgres psql -U shoplab -d shoplab -v top=3 -f /tmp/top-queries.sql
   ```

**Kết quả đo** trên 10 triệu đơn, 3 phút, 16 request song song: 80.178 request (445 request/giây), không lỗi; PostgreSQL chạy 274 nghìn câu thuộc 19 loại, tổng 26,3 giây. Chạy hai lần, cả hai lần cùng 3 câu đứng đầu, số liệu gần như nhau:

| Hạng | Câu SQL | Từ request | Tổng (ms) | % | Số lần | Trung bình (ms) | Trang đọc / lần |
|---|---|---|---|---|---|---|---|
| 1 | `UPDATE products SET stock = stock - $1 ... WHERE id = $2 AND active AND stock >= $3` | `POST /api/orders`, trừ kho cho từng dòng hàng | 7.335 | 27,9 | 23.884 | 0,307 | 5,2 |
| 2 | `select ... from orders where user_id = $1 order by created_at desc, id desc fetch first $2 rows only` | `GET /api/orders?userId=` | 3.107 | 11,8 | 28.118 | 0,111 | 15,4 |
| 3 | `insert into order_items (...)` | `POST /api/orders` | 2.909 | 11,1 | 23.884 | 0,122 | 13,1 |

- **UPDATE products chủ yếu là chờ khoá dòng, không phải chạy chậm.** Câu này chỉ đọc 5 trang mà trung bình chậm gấp ba các câu khác. Lấy mẫu `pg_stat_activity` 400 lần trong lúc gọi API: 19 trên 25 lần bắt gặp nó đang chạy là lúc nó chờ khoá (`Lock:transactionid`), vì đơn khác vừa trừ cùng sản phẩm và giữ khoá dòng tới lúc COMMIT. 50 sản phẩm cho 16 request song song là nhiều "hàng hot"; chạy 8 request song song thì trung bình chỉ còn 0,21 ms. Thời gian chờ khoá được tính vào thời gian chạy của câu.
- **Đơn của một user: 11 dòng mà đọc 15 trang.** Index `idx_orders_user_id_created_at` dẫn thẳng tới đơn của user, nhưng đơn của một người rải khắp bảng (`user_id` tương quan 0,28 với thứ tự trên đĩa) nên gần như mỗi đơn nằm ở một trang riêng; 13.825 trang không có sẵn trong shared buffers. Mỗi lần vẫn chỉ 0,11 ms; câu này đứng thứ hai vì đây là request nhiều nhất (35%).
- **INSERT order_items ghi 13 trang mỗi lần:** ngoài trang của bảng còn cập nhật 3 index (khoá chính, `uk_order_items_order_product`, `idx_order_items_product_id`) và kiểm tra khoá ngoại sang `orders`.

Nên biết khi đọc kết quả:
- **COMMIT của app không có trong pg_stat_statements.** Driver JDBC gửi COMMIT dưới dạng prepared statement có tên (log của PostgreSQL ghi `bind S_1: COMMIT`), và pg_stat_statements không ghi COMMIT chạy theo cách này. Đã thử: gọi `GET /api/users/1` 60 lần thì có 60 lần `BEGIN READ ONLY` nhưng không thêm lần COMMIT nào. COMMIT gõ trong psql / DBeaver, hay của Flyway lúc app khởi động, thì vẫn được ghi. Khi lấy mẫu, phần lớn lần bắt gặp COMMIT là lúc nó chờ ghi WAL xuống đĩa (`IO:WalSync`). Khoảng chờ này không cộng vào câu nào trong bảng, dù khoá dòng của `UPDATE products` được giữ suốt khoảng đó.
- **pg_stat_statements đếm mọi client của DB, không riêng app.** DBeaver chạy thêm nhiều câu đọc metadata (`pg_get_keywords()`, `current_schema`...). App lúc khởi động chạy câu của Flyway (`flyway_schema_history`, `pg_try_advisory_xact_lock`), của Hibernate khi kiểm tra schema (`information_schema.sequences`), và `SET lock_timeout` cho từng connection trong pool (10 lần). Script reset ngay trước khi gọi API nên không tính phần khởi động; tự đếm bằng tay thì reset sau khi app đã chạy xong.
- Thời gian là thời gian chạy trong PostgreSQL (`total_exec_time`): không tính lập kế hoạch, đường truyền, hay phần việc của app. Vd đăng ký trung bình 449 ms là do băm BCrypt trong app; các câu SQL của nó đều dưới 0,3 ms.
- Ngay sau khi khởi động lại Docker, dữ liệu chưa nằm trong bộ nhớ: lượt đầu chậm vì đọc đĩa (cột "Chờ đọc đĩa (ms)" lớn), câu đọc nhiều trang dễ lên đầu. Chạy thêm một lượt để so.

### Index ghép `(user_id, created_at)` so với index đơn `(user_id)` (`scripts/composite-index-lab.sql`)

Câu tốn nhiều thời gian DB nhất ở trên là "đơn của tôi" (`GET /api/orders?userId=`):
```sql
SELECT ... FROM orders WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT 20
```
Thí nghiệm chép `orders` (10 triệu đơn) sang schema `index_lab`, chạy VACUUM ANALYZE, rồi lần lượt với từng index: dựng index → đo 4 câu → bỏ index. Mỗi lúc bản chép chỉ có đúng một index, nên planner chỉ có thể dùng index đó hoặc đọc cả bảng. Ngoài hai index cần so, đo thêm index app đang dùng (migration V11) làm mốc. Không đụng bảng thật, chạy khoảng 1 phút:

```powershell
docker cp scripts/composite-index-lab.sql shoplab-postgres:/tmp/
docker exec shoplab-postgres psql -U shoplab -d shoplab -f /tmp/composite-index-lab.sql
```

| Index | Kích thước | Dựng (10 triệu dòng) |
|---|---|---|
| A. `(user_id)` | 86 MB | 6,2 giây |
| B. `(user_id, created_at)` | 302 MB | 9,5 giây |
| C. `(user_id, created_at DESC, id DESC)`, app đang dùng | 388 MB | 10,9 giây |

Kết quả đo (dữ liệu đã nằm sẵn trong bộ nhớ, thời gian là trung vị 5 lần, tắt truy vấn song song). User 37450 có 10 đơn, gần trung vị; user 1 có nhiều đơn nhất, 1.384 đơn.

| Câu | Index | Plan | Dòng đọc | Trang | ms |
|---|---|---|---|---|---|
| 1. Đơn của tôi, trang đầu, user 10 đơn | A | Limit → Sort → Index Scan | 10 | 13 | 0,048 |
| | B | Limit → Incremental Sort → Index Scan Backward | 10 | 14 | 0,067 |
| | C | Limit → Index Scan | 10 | 14 | 0,030 |
| 2. Đơn của tôi, trang đầu, user 1.384 đơn | A | Limit → Sort (top-N heapsort) → Index Scan | **1.384** | **1.383** | **0,828** |
| | B | Limit → Incremental Sort → Index Scan Backward | 21 | 24 | 0,047 |
| | C | Limit → Index Scan | 20 | 24 | 0,031 |
| 3. Đơn 30 ngày gần nhất, user 1.384 đơn | A | Sort → Index Scan, **bỏ 1.344 dòng** vì sai ngày | 40 | **1.383** | 0,624 |
| | B | Incremental Sort → Index Scan Backward | 40 | 44 | 0,059 |
| | C | Index Scan | 40 | 44 | 0,034 |
| 4. Đếm đơn (câu đếm khi phân trang), user 1.384 đơn | A | Aggregate → Index Only Scan | 1.384 | **10** | 0,168 |
| | B | Aggregate → Index Only Scan | 1.384 | 14 | 0,219 |
| | C | Aggregate → Index Only Scan | 1.384 | 16 | 0,243 |

- **Index ghép cho phép dừng sớm.** Với index đơn, các dòng của một user nằm trong index theo thứ tự lưu, không theo `created_at`. Muốn lấy 20 đơn mới nhất, PostgreSQL phải đọc **hết** 1.384 đơn, sắp xếp, rồi bỏ 1.364 đơn. Với index ghép, dòng của một user đã xếp sẵn theo `created_at`, nên chỉ cần đọc từ đầu tới đủ 20 dòng rồi dừng. *Use The Index, Luke* gọi đây là pipelined top-N. Số trang giảm từ 1.383 xuống 24, tức 57 lần. Khi dữ liệu không nằm sẵn trong bộ nhớ, như máy Windows ở mục trên (khoảng 0,8 ms một trang), 1.383 trang là hơn 1 giây, còn 24 trang chỉ khoảng 20 ms.
- **User ít đơn thì gần như không khác.** Với 10 đơn, cả ba index đều đọc 13–14 trang, vì sắp xếp 10 dòng gần như không tốn gì. Phần lớn user chỉ có khoảng 9 đơn, nên đổi index thì thời gian trung bình gần như không đổi. Cái được là những user nhiều đơn hết bị chậm hẳn.
- **Cột thứ hai lọc được khoảng giá trị.** Ở câu 3, index ghép đi thẳng tới đúng 40 đơn trong 30 ngày (cột đầu so sánh bằng, cột sau lọc khoảng). Index đơn phải đọc cả 1.384 đơn rồi bỏ 1.344.
- **B so với C:** B đọc được theo chiều ngược (`Index Scan Backward`) nên vẫn cho `created_at DESC`. Nhưng câu còn sắp phụ theo `id DESC` để thứ tự giữa các trang cố định, mà B không có `id`, nên PostgreSQL phải thêm `Incremental Sort` (sắp lại trong nhóm cùng `created_at`) và đọc thêm 1 dòng để biết nhóm đã hết. C khớp đúng `ORDER BY` nên không phải sắp gì. Hai cột cùng chiều DESC thì `(user_id, created_at, id)` đọc ngược cũng y như C; `DESC` trong định nghĩa index chỉ cần khi các cột sắp ngược chiều nhau (vd `created_at DESC, id ASC`).
- **Index đơn chỉ thắng ở kích thước và câu đếm.** A nhỏ gần 4 lần vì mỗi `user_id` lặp khoảng 10 lần, PostgreSQL gộp các khoá trùng thành một (deduplication). Khoá ghép thì mỗi dòng một giá trị khác nhau, không gộp được. Câu đếm chỉ cần `user_id`, nên index nhỏ hơn thì đọc ít trang hơn (10 so với 16), nhưng chênh lệch không đáng kể.
- **Kết luận: giữ index C (V11), không thêm index đơn.** Index ghép dùng được cho cả điều kiện chỉ có `user_id = ?` (cột đầu của index), nên có thêm A chỉ tốn chỗ và làm mỗi lần ghi đơn phải cập nhật thêm một index.
- **Phần còn lại là trang của bảng.** Ngay với C, 10 dòng vẫn tốn 14 trang: khoảng 4 trang index, còn lại mỗi đơn một trang bảng, vì đơn của một user nằm rải khắp bảng. Index chỉ quyết định đọc ít hay nhiều **dòng**; muốn giảm trang của bảng thì cần cách khác, vd index chứa sẵn mọi cột câu cần (covering index), hay xếp lại bảng theo user.

### Viết lại `DATE(created_at) = …` thành khoảng thời gian (`scripts/date-range-lab.sql`)

```sql
-- Bọc cột trong hàm: index trên created_at không dùng để tìm được
WHERE DATE(created_at) = '2026-09-09'
-- Khoảng nửa mở trên chính cột, ghi rõ múi giờ của ngày
WHERE created_at >= '2026-09-09 00:00+07' AND created_at < '2026-09-10 00:00+07'
```

Index `idx_orders_created_at` xếp theo giá trị `created_at`, không theo `DATE(created_at)`. Với điều kiện bọc trong hàm, PostgreSQL không biết đi tới đâu trong index, nên phải đọc mọi dòng, tính `DATE()` cho từng dòng rồi so sánh. Viết thành khoảng thì điều kiện nằm thẳng trên cột: index đi tới đầu khoảng và đọc tới cuối khoảng. Dùng khoảng **nửa mở** (`>=` đầu ngày, `<` đầu ngày hôm sau). Không dùng `BETWEEN ... AND '... 23:59:59'`, vì sẽ bỏ sót đơn lúc 23:59:59.5; còn `BETWEEN` tới 00:00 hôm sau thì lấy thừa đơn đúng nửa đêm.

Thí nghiệm chạy thẳng trên bảng thật (chỉ đọc, dùng index sẵn có), khoảng 15 giây:
```powershell
docker cp scripts/date-range-lab.sql shoplab-postgres:/tmp/
docker exec shoplab-postgres psql -U shoplab -d shoplab -f /tmp/date-range-lab.sql
```

Kết quả (ngày 2026-09-09, dữ liệu đã nằm sẵn trong bộ nhớ, thời gian là trung vị 5 lần, tắt truy vấn song song):

| Câu | Cách viết | Plan | Dòng khớp | Dòng bỏ | Trang | ms |
|---|---|---|---|---|---|---|
| Đếm đơn trong ngày | `DATE(created_at) = ngày` | Index Only Scan **cả index** `idx_orders_created_at` | 13.699 | **10.018.333** | **57.890** | **1.451** |
| | khoảng | Index Only Scan `idx_orders_created_at`, chỉ đoạn của ngày đó | 13.699 | 0 | 41 | 1,8 |
| Đơn trong ngày của user 1 (1.384 đơn) | `DATE(created_at) = ngày` | Index Scan `idx_orders_user_id_created_at`, chỉ theo `user_id` | 1 | 1.383 | 1.389 | 1,44 |
| | khoảng | Index Scan `idx_orders_user_id_created_at`, theo `user_id` và khoảng ngày | 1 | 0 | 5 | 0,04 |

- **Đếm đơn trong ngày:** cùng 13.699 đơn, nhưng `DATE()` phải đọc cả 10 triệu dòng; nhanh hơn khoảng 800 lần khi viết thành khoảng. 57.890 trang gồm 27.580 trang index và `Heap Fetches: 32049`. Đó là khoảng 32 nghìn đơn tạo qua API (`top-queries.ps1`) sau lần VACUUM cuối, chưa được đánh dấu all-visible. Autovacuum chỉ tự VACUUM khi số dòng mới lên tới khoảng 20% bảng (2 triệu dòng), nên với bảng lớn, visibility map có thể cũ khá lâu.
- **Đơn trong ngày của một user:** với `DATE()`, index ghép chỉ dùng được cột `user_id`, đọc hết 1.384 đơn của user rồi bỏ 1.383. Với khoảng, cả hai cột của index đều được dùng: 5 trang.
- **`DATE()` còn cho kết quả sai múi giờ.** `created_at` là `timestamptz`, và `DATE(created_at)` cắt ngày theo múi giờ của **phiên làm việc**. Phiên của app và psql ở đây là UTC (`docker-compose.yml` đặt `timezone=UTC`), còn ngày của shop là giờ Việt Nam (+07), bắt đầu lúc 17:00 UTC hôm trước. Đo được: trong phiên UTC, `DATE(created_at) = '2026-09-09'` ra 13.699 đơn, **cùng số lượng nhưng 3.996 đơn (29%) không thuộc ngày 09/09 giờ Việt Nam**. Tổng cộng trông có vẻ đúng, mà nội dung thì sai. Khoảng có ghi `+07` cho cùng một kết quả ở mọi phiên.
- **Index trên `DATE(created_at)` không tạo được:** `functions in index expression must be marked IMMUTABLE`. Kết quả của `DATE()` trên `timestamptz` đổi theo múi giờ của phiên, mà index thì phải cho cùng kết quả mọi lúc. Ghi rõ múi giờ thì tạo được: `((created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)`. Nhưng như vậy là thêm một index nữa (thêm chỗ, thêm việc mỗi lần ghi), và câu truy vấn phải viết đúng y biểu thức đó. Viết thành khoảng thì dùng luôn index đang có.

Trong code Java (`createdAt` là `Instant`), đổi ngày của shop thành khoảng trước khi truy vấn:
```java
ZoneId shopZone = ZoneId.of("Asia/Ho_Chi_Minh");
Instant from = day.atStartOfDay(shopZone).toInstant();              // 00:00 giờ Việt Nam
Instant to = day.plusDays(1).atStartOfDay(shopZone).toInstant();    // 00:00 hôm sau
// where o.createdAt >= :from and o.createdAt < :to
```

### Partial index cho đơn PENDING, covering index cho tổng tiền theo user (`scripts/partial-covering-index-lab.sql`)

App chưa có câu nào cần hai loại index này. Thí nghiệm dùng hai câu thường gặp ở một shop thật:
- **Job tự huỷ đơn chưa thanh toán:** lấy 1.000 đơn PENDING cũ nhất (quá 1 ngày), và đếm số đơn đang treo.
- **Tổng tiền đã mua của một user:** `count(*), sum(total_amount)` của các đơn COMPLETED.

Thí nghiệm chạy trên bảng thật. Đầu tiên VACUUM ANALYZE `orders`, vì Index Only Scan cần visibility map đầy đủ (đơn tạo qua API sau lần VACUUM cuối chưa được đánh dấu). Sau đó với từng phương án: dựng index thử, đo, rồi bỏ index, **trong cùng một câu lệnh**. Lỗi hay Ctrl+C giữa chừng thì cả câu rollback, bảng thật không giữ lại index nào. Lúc dựng index, bảng `orders` không ghi được trong 10–30 giây, nên tắt app hoặc đừng chạy tải cùng lúc.
```powershell
docker cp scripts/partial-covering-index-lab.sql shoplab-postgres:/tmp/
docker exec shoplab-postgres psql -U shoplab -d shoplab -f /tmp/partial-covering-index-lab.sql
```

**A. Partial index** `CREATE INDEX ... ON orders (created_at) WHERE status = 'PENDING'`: chỉ đơn PENDING (khoảng 2% bảng) được đưa vào index.

| Phương án | Kích thước | Dựng | Job: 1.000 đơn cũ nhất | Đếm đơn treo (201 nghìn đơn) |
|---|---|---|---|---|
| A0. Chỉ index sẵn có | | | `idx_orders_created_at` đọc từ đơn cũ nhất, **bỏ 49.325 dòng**: 1.112 trang, 11,3 ms | `idx_orders_status` rồi **ghé bảng 201 nghìn lần** để xem `created_at`: **117.297 trang**, 184 ms (6 giây khi chưa có trong bộ nhớ) |
| A1. Index thường `(status, created_at)` | 386 MB | 17,1 giây | bỏ 0 dòng, 603 trang, 1,5 ms | Index Only Scan, 783 trang, 45 ms |
| A2. Partial `(created_at) WHERE status = 'PENDING'` | **5,2 MB** | **1,2 giây** | bỏ 0 dòng, 600 trang, 0,8 ms | Index Only Scan, 559 trang, 41 ms |

- **Partial nhanh ngang index thường mà nhỏ hơn 74 lần**, dựng nhanh hơn 14 lần. Index chỉ chứa đơn PENDING, nên đặt hay sửa một đơn không phải PENDING không phải ghi gì vào index.
- **Không có index phù hợp**, job phải đọc 50 nghìn dòng mới đủ 1.000 đơn PENDING. Với một job chạy thật, đơn PENDING cũ bị huỷ dần, nên lần sau phải đọc qua nhiều đơn không phải PENDING hơn nữa mới gặp đơn cần. Với partial index, việc này không phụ thuộc bảng lớn cỡ nào.
- **Job vẫn tốn khoảng 600 trang** dù chỉ cần 1.000 dòng: index tìm đúng đơn, nhưng cột `id` nằm ở bảng, và các đơn PENDING nằm rải khắp bảng.
- **Partial index chỉ dùng được khi câu truy vấn chắc chắn nằm trong điều kiện của index.** Đã thử:
  - Câu `status = 'PAID'`: không dùng được, quay về `idx_orders_status`.
  - Câu có tham số `status = $1` (JDBC và Hibernate gửi giá trị kiểu này), `$1 = 'PENDING'`:
    - **custom plan** (plan lập riêng cho giá trị đó) dùng được index partial;
    - **generic plan** (plan lập một lần cho mọi giá trị; PostgreSQL có thể chuyển sang sau 5 lần chạy) không biết `$1` là gì, nên không dùng được, và quay về đọc `idx_orders_created_at` rồi lọc `status = $1`.
  - Vì vậy với câu cần partial index, viết thẳng giá trị `'PENDING'` vào câu SQL (trong JPQL: dùng hằng số) thay vì truyền tham số.

**B. Covering index:** index chứa sẵn mọi cột câu truy vấn cần, PostgreSQL trả kết quả từ index (Index Only Scan) mà không phải ghé bảng. Cột trong `INCLUDE` chỉ nằm ở lá của index, không tham gia sắp xếp hay tìm kiếm, nhưng đọc được để lọc (`status`) và tính (`total_amount`).

| Phương án | Kích thước | Tổng tiền, user 1.384 đơn | Tổng tiền, user 10 đơn |
|---|---|---|---|
| B0. Chỉ index sẵn có `(user_id, created_at DESC, id DESC)` | 388 MB (có sẵn) | Index Scan, **ghé bảng từng đơn**: **1.389 trang**, 2,15 ms | 14 trang, 0,065 ms |
| B1. Thêm `(user_id) INCLUDE (status, total_amount)` | +464 MB | Index Only Scan, Heap Fetches 0: **18 trang**, 0,37 ms | 8 trang, 0,053 ms |
| B2. Thay index hiện có bằng `(user_id, created_at DESC, id DESC) INCLUDE (status, total_amount)` | 637 MB (thay 388 MB, tức +249 MB) | Index Only Scan, Heap Fetches 0: 21 trang, 0,38 ms | 8 trang, 0,045 ms |

- **Lợi ích nằm ở user nhiều đơn:** từ 1.389 xuống 18 trang (ít hơn 77 lần). Với user 10 đơn thì chỉ từ 14 xuống 8 trang.
- **Giá phải trả là dung lượng.** B1 tới 464 MB, lớn hơn cả index `(user_id)` thường (86 MB): index có `INCLUDE` không gộp được các khoá trùng (deduplication), nên mỗi đơn là một mục riêng. B2 dùng lại index đang có nên chỉ thêm 249 MB, và vẫn phục vụ câu "đơn của tôi" như cũ, vì các cột khoá không đổi. Nhưng index của câu đó to ra, trên máy ít RAM thì càng khó nằm hết trong bộ nhớ.
- **Kết luận:** chỉ thêm covering index khi câu tổng tiền thật sự chạy nhiều; dùng `top-queries.ps1` (pg_stat_statements) để biết. Nếu thêm thì chọn B2 (sửa index hiện có) thay vì B1 (thêm một index nữa). Index Only Scan chỉ không phải ghé bảng ở những trang đã được VACUUM đánh dấu all-visible.

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
| Integration test | `OrderIdempotencyIntegrationTests`, `OrderApiIntegrationTests`, `ProductApiIntegrationTests`, `UserApiIntegrationTests`, `PasswordHashingOutsideTransactionTests`, `SqlLoggingTests`, `NaiveStockDeductionTests`, `FlashSaleIntegrationTests`, `NaiveFlashSaleTests`, `TransferDeadlockTests`, `SelfInvocationTrapTests`, `WalletApiIntegrationTests`, `IsolationLevelTests`, `OrderListWithItemsTests`, `NPlusOneFixesTests` | Cả app trên cổng ngẫu nhiên + PostgreSQL thật (Testcontainers), gồm cả kịch bản đồng thời và rollback |
| Test cấu trúc | `ModularityTests` (Spring Modulith + ArchUnit), `DatabaseModularityTests` | Đọc bytecode, kiểm tra ranh giới module và phân tầng trong module; đọc schema, kiểm tra không có khoá ngoại chéo module |

Kịch bản đồng thời dùng `Concurrently.run(n, i -> ...)`: chạy N tác vụ, mỗi tác vụ trên một virtual thread, và một `CountDownLatch(N)` làm vạch xuất phát (mỗi luồng `countDown()` rồi `await()`), nên không tác vụ nào chạy trước khi đủ N luồng sẵn sàng. Kết quả trả theo thứ tự `i`; quá 60 giây thì báo `TimeoutException` và ngắt các tác vụ còn chạy.

**Không lúc xanh lúc đỏ:** toàn bộ 126 test chạy 5 lần liên tiếp đều xanh (mỗi lần ~46 giây); riêng nhóm test đồng thời (deadlock, bán chớp nhoáng, 20 request cùng lúc, bẫy gọi nội bộ...) chạy thêm 10 lần, cũng đều xanh. Test đồng thời không trông vào may rủi: chỗ cần thứ tự xấu nhất thì test ép bằng chốt chờ (`CountDownLatch`), proxy chờ giữa hai lần khoá, hoặc một connection khác giữ khoá rồi chờ request tới đúng câu lệnh (`awaitSessionWaitingForLock`).

Mỗi Spring context của test giữ một pool 10 connection. Testcontainers dựng PostgreSQL riêng cho mỗi context nên không sao; nếu cho cả bộ test dùng chung **một** PostgreSQL thì cần `max_connections` lớn hơn 100 (mặc định), vì bộ test dựng 10 context có DB.

#### Đỏ với bản lỗi, xanh với bản sửa: `scripts/red-green.ps1`

Mỗi kịch bản có test bảo vệ, và một bản lỗi viết dưới dạng patch trong `scripts/bugs/` (bản lỗi không nằm trong code chính hay code test). Script copy `pom.xml`, `mvnw`, `.mvn`, `src` ra thư mục tạm (không đụng thư mục làm việc), chạy test bảo vệ của mọi kịch bản trên code hiện tại (mong đợi **xanh**), rồi lần lượt áp từng patch, chạy test bảo vệ của kịch bản đó (mong đợi **đỏ**) và gỡ patch.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\red-green.ps1                                   # 13 kịch bản, ~5–10 phút
powershell -ExecutionPolicy Bypass -File .\scripts\red-green.ps1 -Scenario deadlock,self-invocation # chỉ vài kịch bản
```

| Kịch bản | Bản lỗi (`scripts/bugs/`) | Test bảo vệ | Bản lỗi làm test đỏ ra sao (đo thật) |
|---|---|---|---|
| `oversell` | `01`: đọc kho → kiểm tra → trừ ở Java → ghi con số | `FlashSaleIntegrationTests`, `NaiveStockDeductionTests#real_conditionalUpdate_sellsExactlyTheStock` | 1.000 lượt mua kho 1 → **10 đơn**; 8 người mua kho 5 → **8** người mua được |
| `deadlock` | `02`: khoá hai ví theo thứ tự tham số | `TransferDeadlockTests#oppositeTransfers_lockingInIdOrder_bothSucceed` | `[TRANSFERRED, DEADLOCK]` thay vì cả hai chuyển xong |
| `deadlock-log` | `03`: log deadlock không có mã | `GlobalExceptionHandlerTests#deadlock_isReportedSeparatelyFromLockTimeout` | log không chứa `SQLState 40P01` |
| `lock-timeout` | `04`: bỏ `SET lock_timeout` | `OrderIdempotencyIntegrationTests#keyHeldByAnotherTransaction_returns409AfterLockTimeout` | request chờ khoá mãi, client hết giờ |
| `profile-lost-update` | `05`: không so `version` client gửi (hồ sơ) | `UserApiIntegrationTests#updateProfile_staleVersion_returns409` | `200` thay vì `409`, ghi đè thay đổi của người khác |
| `version-409` | `06`: không bắt `ObjectOptimisticLockingFailureException` | `UserApiIntegrationTests#profileChangedBetweenCheckAndWrite_versionColumnReturns409` | `500` thay vì `409` |
| `product-lost-update` | `07`: không so `version` client gửi (sản phẩm) | `ProductApiIntegrationTests#patch_staleVersion_returns409` | `200` thay vì `409` |
| `stock-adjustment` | `08`: đọc kho → cộng ở Java → ghi con số tuyệt đối | `ProductApiIntegrationTests#restocksAndOrdersAtOnce_loseNoUpdate` | kho **10** thay vì 20: mất lượt nhập / đặt hàng |
| `duplicate-email` | `09`: migration bỏ `uk_users_email`, chỉ còn kiểm tra trước | `UserApiIntegrationTests#duplicateEmailCaughtByDatabase_returnsDuplicateEmail`, `#sameEmailAtOnce_registersExactlyOne` | 20 đăng ký cùng email → **20** người dùng |
| `unique-500` | `10`: không dịch `uk_wallets_user`, không có handler dự phòng | `GlobalExceptionHandlerTests#untranslatedDataIntegrityViolation_returnsGeneric409`, `WalletApiIntegrationTests#duplicateWalletCaughtByDatabase_returnsDuplicateWallet` | `500` thay vì `409` |
| `self-invocation` | `11`: cả ba cách sửa quay về `this.transfer(...)` | 3 test sửa của `SelfInvocationTrapTests` | A = **60.00** thay vì 90.00 (mất 30) |
| `hash-in-transaction` | `12`: `register` là một transaction, băm mật khẩu bên trong | `PasswordHashingOutsideTransactionTests#register_hashesPasswordOutsideTransaction` | lúc băm `transactionActive=true, activeConnections=1` |
| `n-plus-one` | `13`: mỗi đơn tự nạp dòng hàng (`order.getItems().size()` trong vòng lặp) | `OrderListWithItemsTests` | **102 câu SQL** cho trang 100 đơn (22 câu cho trang 20 đơn) thay vì 2 |

```
== Ma trận
   Kịch bản             Bản sửa      Bản lỗi
   oversell             XANH         ĐỎ
   deadlock             XANH         ĐỎ
   ...
   hash-in-transaction  XANH         ĐỎ
   n-plus-one           XANH         ĐỎ

ĐÚNG (13/13): mọi kịch bản có test xanh với bản sửa và đỏ với bản lỗi
```

- **Kịch bản race** (`oversell`, `stock-adjustment`): không ép thứ tự thì lỗi không phải lần nào cũng lộ (bản ngây thơ bán vượt: 5 lần chạy cho 6, 10, 9, **1**, 10 đơn). Bản lỗi được chạy tối đa `-Attempts` lần (mặc định 3), đỏ ở bất kỳ lần nào là đạt; script in ra lần thứ mấy thì đỏ.
- **Patch gắn với code hiện tại.** Code đổi mà patch không áp được nữa thì script báo `PATCH KHÔNG ÁP ĐƯỢC` cho kịch bản đó; sửa lại bản lỗi rồi tạo lại patch bằng `git diff`.
- Cần git và Docker (như khi chạy test). Mã thoát `0` nếu mọi kịch bản xanh với bản sửa và đỏ với bản lỗi; `1` nếu không; `2` nếu không chạy được. `-KeepTemp` giữ lại thư mục tạm để xem.

### Test thủ công

- **Postman:** import `shoplab.postman_collection.json` → *Run collection* (chạy đúng thứ tự). Biến `baseUrl` mặc định `http://localhost:8080`.
- **File `.http`** (IntelliJ / VS Code REST Client): `products.http`, `users.http`.
- **Bán chớp nhoáng với app đang chạy:** `scripts\flash-sale.ps1` (xem mục *Test bán chớp nhoáng*).
- **Deadlock chuyển tiền:** `scripts\transfer-deadlock.ps1` (xem mục *Deadlock*).
- **Lost update khi sửa hồ sơ:** `scripts\profile-lost-update.ps1` (xem mục *Sửa hồ sơ: bắt buộc gửi `version`*).
- **Bẫy gọi nội bộ `@Transactional`:** `scripts\self-invocation-trap.ps1` (chạy test, cần Docker; xem mục *Bẫy @Transactional: gọi nội bộ*).
- **Đỏ với bản lỗi, xanh với bản sửa cho mọi kịch bản:** `scripts\red-green.ps1` (chạy test, cần Docker; xem mục *Đỏ với bản lỗi, xanh với bản sửa*).
- **Lost update khi sửa sản phẩm:** `scripts\product-lost-update.ps1` (xem mục *Sửa sản phẩm: bắt buộc gửi `version`*).
- **Nhập / trừ tồn kho cùng lúc với đơn hàng, gửi lại cùng key:** `scripts\stock-adjustment.ps1` (xem mục *Điều chỉnh tồn kho*).
- **Đăng ký trùng email / username cùng lúc:** `scripts\duplicate-email.ps1` (xem mục *Đăng ký trùng gửi cùng lúc*).
- **Đếm số câu SQL của danh sách 100 đơn kèm dòng hàng (N+1):** `scripts\n-plus-one.ps1` (xem mục *N+1*).
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
| PostgreSQL `shared_preload_libraries` (`docker-compose.yml`) | `pg_stat_statements` | Cộng dồn số lần chạy, thời gian của từng câu SQL để tìm câu tốn nhất (`scripts/top-queries.ps1`). Chỉ nạp được lúc khởi động: đổi thì phải tạo lại container. Đi cùng `track_io_timing=on` để có thời gian chờ đọc đĩa |
| PostgreSQL `shared_buffers` (`docker-compose.yml`) | `1GB` | Mặc định 128MB. Bằng 1/4 RAM khi cấp cho Docker 4 GB, giữ được phần lớn index của bộ dữ liệu lớn trong bộ nhớ PostgreSQL |
| Container `shm_size` (`docker-compose.yml`) | `1g` | `/dev/shm` mặc định của Docker chỉ 64MB, truy vấn song song đặt bảng băm chung ở đó. Đã thử trên 10 triệu dòng: `SET work_mem = '256MB'` rồi join thì 64MB báo `could not resize shared memory segment ... No space left on device`, 1g chạy được |
| `spring.jpa.properties.hibernate.generate_statistics` | `true` | Thống kê của Hibernate: hết mỗi request log số câu JDBC đã chạy (`Logging session metrics`), thấy ngay N+1. Logger `org.hibernate.session.metrics` nằm trong nhóm `sql` (khai báo lại nhóm có sẵn của Spring Boot để thêm logger này) |
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
| `GET` | `/api/orders/with-items?userId=1&page=0&size=100` | **200**: như trên nhưng mỗi đơn kèm dòng hàng (cùng dạng `GET /api/orders/{id}`); trang bao nhiêu đơn cũng chỉ 2 câu SQL (xem mục *N+1*) | như trên |

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

#### N+1: danh sách đơn kèm dòng hàng (`GET /api/orders/with-items`)

Cách viết tự nhiên nhất là lấy trang đơn, rồi để từng đơn tự nạp dòng hàng của nó (vd `order.getItems().size()`, hay để Jackson đọc `items` khi còn session). Mỗi đơn lúc đó chạy thêm một câu `SELECT ... FROM order_items WHERE order_id = ?`, nên trang 100 đơn tốn **1 + 100 câu** (cộng 1 câu đếm cho phân trang): đó là N+1.

App dùng `@EntityGraph` trên chính câu phân trang (`OrderRepository.findWithItemsByUserId`):
```java
@EntityGraph(attributePaths = "items")
Page<Order> findWithItemsByUserId(Long userId, Pageable pageable);
```
Hibernate 7 sinh **một** câu: phân trang trên riêng bảng `orders` trong một subquery, rồi mới join `order_items`. Giới hạn vì vậy áp lên số đơn, không phải số dòng sau khi join:
```sql
select ... from (select ... from orders o1_0 where o1_0.user_id = ?
                 order by o1_0.created_at desc, o1_0.id desc offset ? rows fetch first ? rows only) o1_0
left join order_items i1_0 on o1_0.id = i1_0.order_id
order by o1_0.created_at desc, o1_0.id desc, i1_0.id
```
Hibernate 5, 6 không làm vậy: gặp phân trang trên câu có fetch collection, chúng nạp **mọi** đơn của người dùng rồi mới cắt trang trong bộ nhớ, kèm cảnh báo `HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory`. Khi đó phải dùng nạp theo lô, hoặc tách làm hai bước (bảng dưới).

**So sánh các cách sửa** (`order.internal.NPlusOneFixesTests`): trang 2 (20 đơn) của người dùng có 100 đơn, mỗi đơn 2 dòng hàng. Đếm bằng thống kê Hibernate: số câu SQL, số đơn và dòng hàng đã nạp vào bộ nhớ. Một trang đúng chỉ cần 20 đơn, 40 dòng hàng.

| Cách | Câu SQL | Đơn / dòng hàng nạp | Ghi chú |
|---|---|---|---|
| N+1: lấy trang, rồi `order.getItems().size()` từng đơn | **22** | 20 / 40 | trang, đếm, 20 lần `... from order_items where order_id = ?` |
| **`@EntityGraph(attributePaths = "items")` trên câu phân trang** (app dùng) | **2** | 20 / 40 | trang đơn kèm dòng hàng, đếm |
| `JOIN FETCH` trong câu phân trang (`@Query` + `countQuery`) | 2 | 20 / 40 | như `@EntityGraph`; phải viết câu đếm riêng vì Spring Data không tự suy ra câu đếm từ câu có fetch join |
| Nạp theo lô (batch size 100): code y như N+1 | 3 | 20 / 40 | lần đầu chạm `items` của một đơn, Hibernate nạp `items` của mọi đơn trong session bằng một câu `... where order_id = any (?)` |
| Hai bước: trang đơn, rồi `JOIN FETCH` dòng hàng `where o.id in :ids` | 3 | 20 / 40 | cách sửa quen thuộc trên Hibernate 5, 6 |

Không cách sửa nào có cảnh báo `HHH90003004` (test kiểm tra điều đó).

- **`@EntityGraph` và `JOIN FETCH`** cho ít câu nhất. `@EntityGraph` không phải viết JPQL, câu đếm Spring Data tự sinh. Hợp với một màn hình cụ thể cần dữ liệu đi kèm.
- **Nạp theo lô** không đổi code gọi, sửa N+1 cho **mọi** chỗ chạm vào quan hệ đó. Bật bằng `@BatchSize(size = 100)` trên `Order.items`, hoặc `spring.jpa.properties.hibernate.default_batch_fetch_size=100` cho mọi quan hệ. Nạp dòng hàng cho N đơn tốn ⌈N/100⌉ câu (trang 20 đơn: 1 câu). Phần chạm vào `items` vẫn phải nằm trong transaction (app tắt `open-in-view`). App chưa bật nạp theo lô: nếu bật toàn cục, bản N+1 trong patch 13 cũng sẽ chỉ còn 3 câu, và test bảo vệ không còn bắt được N+1.
- **Hai bước** tốn thêm một câu, nhưng không phụ thuộc vào việc Hibernate có đẩy phân trang vào subquery hay không.

**Đếm số câu SQL:**
- Thống kê Hibernate (`spring.jpa.properties.hibernate.generate_statistics=true`): hết mỗi request, log của app có khối `Logging session metrics`, ngay sau dòng `COMMIT OrderService.listByUserWithItems`:
  ```
  org.hibernate.session.metrics : HHH000401: Logging session metrics:
      ... ns preparing 2 JDBC statements
      ... ns executing 2 JDBC statements
  ```
  Hibernate 7 log khối này ở mức DEBUG. Logger của nó (`org.hibernate.session.metrics`) được thêm vào nhóm `sql`, nên tắt log SQL (`--logging.level.sql=INFO`) là tắt luôn khối này.
- Test `OrderListWithItemsTests`: tạo 100 đơn, mỗi đơn 2 dòng hàng; gọi API; khẳng định không quá 2 câu (thống kê Hibernate), và trang 20 đơn chỉ nạp đúng 20 đơn vào bộ nhớ (nạp cả 100 rồi cắt trong bộ nhớ thì test đỏ).
- Script gọi API thật trên app đang chạy, đếm bằng `pg_stat_statements` ở phía DB (cần PostgreSQL đã nạp `pg_stat_statements`, xem mục *Câu SQL tốn thời gian nhất*):
  ```powershell
  powershell -ExecutionPolicy Bypass -File .\scripts\n-plus-one.ps1     # -Orders 20 để thử trang khác
  ```
  Script đăng ký một người dùng mới, tạo 2 sản phẩm, đặt 100 đơn qua API, reset `pg_stat_statements`, gọi `GET /api/orders/with-items?size=100` đúng một lần, rồi in từng câu SQL và số lần chạy.

| | Trang 100 đơn | Trang 20 đơn |
|---|---|---|
| Bản N+1 (`scripts/bugs/13-n-plus-one.patch`) | **102 câu**: 1 trang đơn + 1 đếm + 100 lần nạp dòng hàng | **22 câu** |
| Bản hiện tại (`@EntityGraph`) | **2 câu**: trang đơn kèm dòng hàng, đếm | **2 câu** |

Đã thử: test đếm được 2 câu với bản hiện tại. Áp patch N+1 (`scripts\red-green.ps1 -Scenario n-plus-one`) thì test đỏ với 102 và 22 câu. Script trên app thật in đúng 2 câu đọc dữ liệu (không tính `BEGIN READ ONLY`), khớp con số trong khối `Logging session metrics` của log.

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
   Test `profileChangedBetweenCheckAndWrite_versionColumnReturns409` ép đúng nhánh này: một connection khác sửa hồ sơ
   (chưa commit) trong lúc request đã qua bước 1 và đang chờ ở câu `UPDATE`. Bỏ handler
   `ObjectOptimisticLockingFailureException` trong `GlobalExceptionHandler` thì request này thành 500 và test đỏ.

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
| Hiện tại (cả 2 lớp) | **1** | 49 (cả 49 do DB chặn) | **1** |
| Bỏ `uk_users_email` (chỉ lớp 1) | **50** | 0 | **50** |
| Bỏ phần dịch lỗi DB (chỉ còn constraint) | 1 | các request do DB chặn nhận `409 data-integrity` thay vì `duplicate-email` | 1 |
| Bỏ lớp 1 (chỉ còn constraint) | 1 | 49 | 1 |

- **Bỏ constraint thì cả 50 request đều tạo được người dùng.** Lớp 1 là một transaction chỉ đọc vài ms, sau đó băm mật khẩu ~80 ms ngoài transaction (xem *Không gọi API ngoài hay chờ lâu bên trong transaction*), nên cả 50 request đều kiểm tra xong trước khi request đầu tiên INSERT. Trước khi chuyển việc băm ra ngoài transaction, số đo là 40 chặn ở lớp 1, 9 do DB, và bỏ constraint thì "chỉ" 10 người dùng trùng: mỗi request giữ một connection suốt lúc băm, nên pool 10 connection vô tình giới hạn số request qua lớp 1 cùng lúc.
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
== Case 1: 50 request đăng ký cùng email dup1791511946@example.com, username khác nhau, gửi cùng lúc
   xong sau 2470 ms
   201: 50
        Người 1 (id 40, email dup1791511946@example.com, username dup1791511946-1)
        Người 2 (id 17, email dup1791511946@example.com, username dup1791511946-2)
        ...
   409 duplicate-email: 0
   SAI: 50 người dùng được tạo, mong đợi đúng 1
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
| `BatchTransferRunner.transferAll` (bean khác) → `batch.transfer(...)` (**sửa: tách sang bean khác**) | Có | Rollback, A vẫn 100 | 90 / 110 / **200** |
| `transferAllThroughProxy` → `self.transfer(...)` (**sửa: gọi qua proxy**) | Có | Rollback, A vẫn 100 | 90 / 110 / **200** |
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

1. **Tách sang bean khác** (`BatchTransferRunner`, test `transferAllFromAnotherBean_keepsMoney`): vòng lặp nằm ở một bean, `transfer` ở bean kia. Spring tiêm vào proxy của bean kia, nên `transfers.transfer(...)` đi qua proxy. Cách nên dùng: nhìn code là thấy lời gọi đi sang bean khác, không cần nhớ quy tắc gì.
2. **Gọi qua proxy** (`transferAllThroughProxy`, test `transferAllThroughProxy_keepsMoney`): bean tự inject chính mình (`@Lazy BatchTransferService self`) rồi gọi `self.transfer(...)` thay cho `this.transfer(...)`. Chạy được, nhưng người sau "dọn" `self.transfer` thành `transfer` là bẫy quay lại (thử rồi: A = 60.00, test đỏ). `AopContext.currentProxy()` cũng là cách gọi qua proxy, nhưng phải bật `exposeProxy`.
3. **Tự mở transaction bằng `TransactionTemplate`** cho từng lượt (`transferAllEachInOwnTransaction`): rõ ràng, không phụ thuộc proxy.

Bẫy này áp dụng cho mọi annotation chạy nhờ proxy: `@Transactional`, `@Async`, `@Cacheable`, `@Retryable`...; method `private` thì không bao giờ qua proxy.

"Phá" để chắc test bắt được lỗi:

| Phá | Test fail |
|---|---|
| Bản sửa gọi thẳng `transfer(...)`, bỏ `TransactionTemplate` | `transferAllEachInOwnTransaction_keepsMoney`: A expected 90.00 but was **60.00** |
| Bỏ `@Transactional` trên `transfer` | `transfer_calledThroughProxy_rollsBack`: không còn proxy, lượt lỗi không rollback |
| Bản "gọi qua proxy" đổi `self::transfer` thành `this::transfer` | `transferAllThroughProxy_keepsMoney`: A expected 90.00 but was **60.00** |

```bash
./mvnw test -Dtest=SelfInvocationTrapTests
```

#### Tự chạy: `scripts/self-invocation-trap.ps1`

Bản lỗi chỉ có trong test, app không có API nào đi vào nó, nên script **chạy test** `SelfInvocationTrapTests` qua Maven (cần Docker đang chạy, như khi chạy test), rồi đọc log `BEGIN` / `COMMIT` / `ROLLBACK`, SQL và các dòng `[self-invocation]` mà test ghi ra, in diễn biến từng lượt chuyển của 4 case theo thứ tự: qua proxy → bẫy → sửa → code thật.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\self-invocation-trap.ps1          # 6 case, mong đợi ĐÚNG
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

-- 3. SỬA (tách sang bean khác): BatchTransferRunner gọi batch.transfer(...) qua proxy
   ...
   kết quả: [FAILED,TRANSFERRED]; transaction trong transfer: CÓ; A = 90.00, B = 110.00, tổng 200.00
   đúng như mong đợi

-- 4. SỬA (gọi qua proxy): self.transfer(...) thay cho this.transfer(...)
   ...
   kết quả: [FAILED,TRANSFERRED]; transaction trong transfer: CÓ; A = 90.00, B = 110.00, tổng 200.00
   đúng như mong đợi

-- 5. SỬA: mỗi lượt chạy trong transaction mở bằng TransactionTemplate
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
ĐÚNG (6/6 case): gọi nội bộ this.transfer(...) không có transaction và làm mất 30;
      tách sang bean khác, gọi qua proxy hoặc TransactionTemplate thì lượt lỗi rollback, tổng tiền giữ 200
```

`-Break` truyền `-Dshoplab.trap.break=true` cho test: `transferAllEachInOwnTransaction` bỏ `TransactionTemplate`, gọi thẳng `transfer(...)` như bản bẫy. Case 5 lúc đó ra `MẤT 30.00`, test đỏ ở `expected: 90.00 but was: 60.00`, và script kết luận `ĐÚNG: đã phá bản sửa và test bắt được`. Không cần sửa code, chạy lại không có `-Break` là về như cũ.

Mã thoát `0` nếu đúng như mong đợi (không `-Break`: mọi test xanh; `-Break`: đúng test của bản sửa đỏ); `1` nếu không; `2` nếu không chạy được test (vd Docker chưa chạy). Trên macOS / Linux: `pwsh ./scripts/self-invocation-trap.ps1`.

### Không gọi API ngoài hay chờ lâu bên trong transaction

Transaction giữ một connection của pool (10 connection) từ `BEGIN` tới `COMMIT`. Việc gì chậm mà không cần DB nằm trong khoảng đó thì connection bị giữ không, và request khác phải xếp hàng chờ connection. Rà toàn bộ `src/main`:

| Kiểm tra | Kết quả |
|---|---|
| Gọi API ngoài (`RestClient`, `RestTemplate`, `WebClient`, `HttpClient`, mail, message queue) | Không có |
| `Thread.sleep`, chờ `Future` / `CompletableFuture`, `@Async`, event listener | Không có |
| `spring.jpa.open-in-view` | `false`: không giữ `EntityManager` (và connection) suốt request |
| Chờ khoá DB (`SELECT ... FOR UPDATE`, câu `UPDATE` cùng dòng, idempotency key) | Có, nhưng có giới hạn: `lock_timeout` 5 giây, deadlock bị phát hiện sau 1 giây |
| Việc chậm không cần DB | **Có một chỗ, đã sửa:** `UserService.register` băm mật khẩu BCrypt (cố ý chậm, ~80 ms) bên trong transaction |

**Sửa `register`:** tách thành 3 bước, chỉ bước 1 và 3 có transaction:

```
1. transaction ngắn, chỉ đọc: kiểm tra trùng email / username   (trùng → 409, khỏi băm)
2. băm mật khẩu                                                (KHÔNG transaction, KHÔNG giữ connection)
3. transaction ngắn: INSERT user + account                     (trùng do request khác chen vào → unique constraint chặn → 409)
```

`register` dùng `@Transactional(propagation = SUPPORTS)` để đè `@Transactional(readOnly = true)` của class (bản thân method không mở transaction; bên gọi đã có transaction, vd test, thì chạy chung), bước 1 và 3 dùng `TransactionTemplate`. Log thật, app đã chạy nóng:

| | Transaction | Thời gian giữ connection |
|---|---|---|
| Trước | `BEGIN UserService.register` … `select` … *(băm ~77 ms)* … `insert` … `COMMIT` | ~88 ms |
| Sau | `BEGIN … check duplicates` … `COMMIT` (5 ms) · *băm ~79 ms, không giữ gì* · `BEGIN … insert` … `COMMIT` (8 ms) | ~13 ms |

Test `PasswordHashingOutsideTransactionTests`: bọc `PasswordEncoder` thật bằng spy, mỗi lần `encode` ghi lại có transaction không và pool đang cho mượn bao nhiêu connection. Mong đợi `transactionActive=false, activeConnections=0`; đăng ký trùng email thì `encode` không được gọi. Thử đưa `register` về bản cũ: test đỏ với `transactionActive=true, activeConnections=1`.

Đánh đổi: bước 1 ngắn nên khi nhiều request **cùng email** đến cùng lúc, cả đám qua được bước 1 và đều băm mật khẩu trước khi unique constraint chặn ở bước 3 (đo: 50 request → 1 × `201`, 49 × `409`, cả 49 do DB chặn). Đăng ký lại một email **đã có** thì vẫn bị chặn trước khi băm.

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

### Vi phạm unique: luôn 409, không bao giờ 500

Mỗi ràng buộc unique mà API có thể chạm tới đều có hai lớp: service kiểm tra trước (trả lỗi rõ nghĩa), và nếu nhiều request cùng lọt qua thì service bắt lỗi DB theo **tên constraint** (`DbConstraints.isViolated`) để trả đúng loại lỗi. Lỗi unique nào service chưa dịch thì `GlobalExceptionHandler` vẫn trả `409 data-integrity` (`DataIntegrityViolationException`, gồm cả `DuplicateKeyException` khi đi qua `JdbcClient`), không rơi xuống 500.

| Ràng buộc | Khi nào vi phạm | Lỗi | Test cho trường hợp DB chặn (lọt qua bước kiểm tra trước) |
|---|---|---|---|
| `uk_users_email` | Đăng ký trùng email (không phân biệt hoa/thường) | 409 `duplicate-email` | `duplicateEmailCaughtByDatabase_returnsDuplicateEmail`, `sameEmailAtOnce_registersExactlyOne` |
| `uk_accounts_username` | Đăng ký trùng username | 409 `duplicate-username` | `sameUsernameAtOnce_registersExactlyOne` |
| `uk_products_sku` | Tạo, hoặc `PATCH` sang SKU đã có | 409 `duplicate-sku` | `duplicateSkuCaughtByDatabase_returnsDuplicateSku` (tạo), `duplicateSkuOnPatchCaughtByDatabase_returnsDuplicateSku` (`PATCH`) |
| `uk_wallets_user` | Tạo ví thứ hai cho cùng người dùng | 409 `duplicate-wallet` | `duplicateWalletCaughtByDatabase_returnsDuplicateWallet` |
| `uk_order_items_order_product` | Không chạm được qua API: dòng trùng sản phẩm được gộp trước khi ghi | — | `OrderModuleTests` (gộp dòng trùng) |
| `uk_accounts_user`, khoá chính | Không chạm được qua API (mỗi người dùng tạo đúng một tài khoản, id lấy từ sequence) | 409 `data-integrity` nếu có | — |
| `idempotency_keys` (khoá chính) | `INSERT ... ON CONFLICT DO NOTHING`, không ném lỗi | — | `OrderIdempotencyIntegrationTests` |

"Phá" để thấy lớp nào chặn gì: bỏ phần dịch `uk_wallets_user` trong `WalletService` thì request vẫn nhận **409**, chỉ đổi thành `data-integrity` (test đỏ vì sai `type`); bỏ thêm handler `DataIntegrityViolationException` thì mới thành **500**.

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