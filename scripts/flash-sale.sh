#!/usr/bin/env bash
# Bán chớp nhoáng: BUYERS lượt mua cùng lúc một sản phẩm chỉ còn STOCK cái, rồi đếm số đơn được tạo.
#
# Cần: app đang chạy (./mvnw spring-boot:run), curl, jq. Trên Windows chạy trong Git Bash.
#
# Dùng:
#   ./scripts/flash-sale.sh                                # 1000 lượt mua, kho 1, 200 lượt song song
#   BUYERS=500 STOCK=3 PARALLEL=100 ./scripts/flash-sale.sh
#   BASE_URL=http://localhost:9090 ./scripts/flash-sale.sh
#
# Thoát với mã 0 nếu bán đúng min(STOCK, BUYERS) cái và mọi lượt còn lại đều nhận "hết hàng" (insufficient-stock);
# 1 nếu sai; 2 nếu không chạy được.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BUYERS="${BUYERS:-1000}"
STOCK="${STOCK:-1}"
PARALLEL="${PARALLEL:-200}"
RUN="$(date +%s)"   # mã của lần chạy: email, SKU, Idempotency-Key không trùng lần trước

for cmd in curl jq xargs seq; do
  command -v "$cmd" >/dev/null || { echo "Thiếu lệnh '$cmd'" >&2; exit 2; }
done
curl -s -o /dev/null --max-time 5 "$BASE_URL/api/products" \
  || { echo "Không gọi được $BASE_URL. App đã chạy chưa? (./mvnw spring-boot:run)" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# POST JSON, in body ra stdout. Mã HTTP không phải 2xx thì in lỗi và dừng.
post_json() {
  local path=$1 body=$2 status
  status=$(curl -s -o "$TMP/setup.json" -w '%{http_code}' -X POST "$BASE_URL$path" \
    -H 'Content-Type: application/json' -d "$body")
  if [[ $status != 2* ]]; then
    echo "POST $path → $status: $(cat "$TMP/setup.json")" >&2
    exit 2
  fi
  cat "$TMP/setup.json"
}

echo "== Bước 2: tạo người mua"
USER_ID=$(post_json /api/users \
  "{\"email\":\"flash$RUN@example.com\",\"fullName\":\"Người mua $RUN\",\"username\":\"flash$RUN\",\"password\":\"matkhau123\"}" \
  | jq -r .id)
echo "   userId = $USER_ID"

echo "== Bước 3: tạo sản phẩm còn $STOCK cái"
PRODUCT_ID=$(post_json /api/products \
  "{\"sku\":\"FLASH-$RUN\",\"name\":\"Hàng hiếm $RUN\",\"category\":\"test\",\"price\":100000,\"stock\":$STOCK}" \
  | jq -r .id)
echo "   productId = $PRODUCT_ID"

echo "== Bước 4: $BUYERS lượt mua, $PARALLEL lượt chạy song song"
# Mỗi lượt một Idempotency-Key riêng nên là một lần mua khác nhau, không phải gửi lại.
# Body của từng response ghi vào file riêng; stdout chỉ có mã HTTP, mỗi dòng một mã.
SECONDS=0
seq "$BUYERS" | xargs -P "$PARALLEL" -I{} curl -s --max-time 60 -o "$TMP/{}.json" -w '%{http_code}\n' \
  -X POST "$BASE_URL/api/orders" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: flash-$RUN-{}" \
  -d "{\"userId\":$USER_ID,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":1}]}" \
  > "$TMP/codes"
echo "   xong sau ~${SECONDS} giây"

echo "   Mã HTTP (000 = không kết nối được):"
sort "$TMP/codes" | uniq -c | sed 's/^/     /'
echo "   Loại lỗi:"
find "$TMP" -name '[0-9]*.json' -exec cat {} + | jq -r '.type // empty' | sed 's|.*/||' > "$TMP/types"
sort "$TMP/types" | uniq -c | sed 's/^/     /'

echo "== Bước 5: đếm đơn và xem kho"
ORDERS=$(curl -s "$BASE_URL/api/orders?userId=$USER_ID&size=1" | jq '.page.totalElements')
STOCK_LEFT=$(curl -s "$BASE_URL/api/products/$PRODUCT_ID" | jq '.stock')
CREATED=$(grep -c '^201$' "$TMP/codes" || true)
OUT_OF_STOCK=$(grep -c '^insufficient-stock$' "$TMP/types" || true)
EXPECTED=$(( STOCK < BUYERS ? STOCK : BUYERS ))
echo "   số đơn trong DB : $ORDERS (mong đợi $EXPECTED)"
echo "   response 201    : $CREATED (mong đợi $EXPECTED)"
echo "   hết hàng        : $OUT_OF_STOCK (mong đợi $(( BUYERS - EXPECTED )))"
echo "   kho còn         : $STOCK_LEFT (mong đợi $(( STOCK - EXPECTED )))"

if [[ $ORDERS -eq $EXPECTED && $CREATED -eq $EXPECTED && $OUT_OF_STOCK -eq $(( BUYERS - EXPECTED ))
      && $STOCK_LEFT -eq $(( STOCK - EXPECTED )) ]]; then
  echo "ĐÚNG: bán đúng $EXPECTED cái, các lượt còn lại đều nhận \"hết hàng\""
elif [[ $ORDERS -gt $EXPECTED ]]; then
  echo "SAI: bán vượt, $ORDERS đơn cho $STOCK cái hàng" >&2
  exit 1
else
  echo "SAI: số đơn không vượt, nhưng có lượt mua nhận lỗi khác \"hết hàng\" (xem Loại lỗi ở trên)" >&2
  exit 1
fi
