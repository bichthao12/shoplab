package com.shoplab.product.internal;

import com.shoplab.product.InsufficientStockException;
import com.shoplab.product.ProductInventory;
import com.shoplab.product.ProductUnavailableException;
import com.shoplab.product.ReservedItem;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * BẢN NGÂY THƠ của ProductInventory: chỉ có trong test, để so sánh với DefaultProductInventory. Không dùng trong app.
 *
 * Giữ hàng bằng 4 bước tách rời, không khoá gì:
 * <pre>
 *   1. đọc tồn kho        SELECT thường, không FOR UPDATE
 *   2. kiểm tra còn hàng   so với con số vừa đọc
 *   3. trừ ở Java          stock - quantity
 *   4. lưu
 * </pre>
 * Từ bước 1 tới lúc commit, giao dịch khác vẫn đọc được đúng con số cũ: hai giao dịch cùng đọc stock = 5
 * thì cùng thấy "còn hàng", cùng tính 5 - 1 = 4 và cùng ghi 4.
 *
 * Bước 4 có hai cách, cho hai kết quả khác nhau (xem NaiveStockDeductionTests):
 * <ul>
 *   <li>PLAIN_UPDATE: {@code UPDATE products SET stock = ? WHERE id = ?}. Lần ghi sau đè lên lần ghi trước
 *       (lost update): ai cũng mua được, kho chỉ giảm như thể có một người mua.</li>
 *   <li>ENTITY_WITH_VERSION: lưu qua entity Product. {@code @Version} thêm {@code AND version = ?} vào câu UPDATE,
 *       nên lần ghi sau không khớp version → lỗi xung đột, rollback. Không bán vượt, nhưng chỉ người ghi đầu tiên
 *       mua được, dù kho còn đủ cho người khác.</li>
 * </ul>
 */
class NaiveProductInventory implements ProductInventory {

    enum SaveMode { PLAIN_UPDATE, ENTITY_WITH_VERSION }

    private final ProductRepository repo;
    private final JdbcClient jdbc;
    private final SaveMode saveMode;
    private final Runnable beforeSave;

    /**
     * @param beforeSave chạy giữa bước kiểm tra và bước lưu. Test dùng để giữ mọi giao dịch ở đó tới khi tất cả
     *                   đã đọc xong: thứ tự xấu nhất, ngoài đời xảy ra ngẫu nhiên khi nhiều người cùng mua.
     */
    NaiveProductInventory(ProductRepository repo, JdbcClient jdbc, SaveMode saveMode, Runnable beforeSave) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.saveMode = saveMode;
        this.beforeSave = beforeSave;
    }

    @Override
    public List<ReservedItem> reserveStock(Map<Long, Integer> quantities) {
        List<ReservedItem> reserved = new ArrayList<>();
        for (Map.Entry<Long, Integer> line : new TreeMap<>(quantities).entrySet()) {
            long productId = line.getKey();
            int quantity = line.getValue();

            // 1. Đọc tồn kho
            Product p = repo.findById(productId)
                    .filter(Product::isActive)
                    .orElseThrow(() -> new ProductUnavailableException("Sản phẩm id = " + productId + " không bán được"));

            // 2. Kiểm tra còn hàng
            if (p.getStock() < quantity) {
                throw new InsufficientStockException(p.getSku(), quantity, p.getStock());
            }

            beforeSave.run();

            switch (saveMode) {
                case PLAIN_UPDATE -> {
                    int newStock = p.getStock() - quantity;                      // 3. trừ ở Java
                    jdbc.sql("UPDATE products SET stock = :stock WHERE id = :id") // 4. lưu con số đã tính
                            .param("stock", newStock)
                            .param("id", productId)
                            .update();
                }
                case ENTITY_WITH_VERSION -> {
                    p.deductStock(quantity);   // 3. trừ ở Java
                    repo.save(p);              // 4. lưu: lúc commit chạy UPDATE ... WHERE id = ? AND version = ?
                }
            }
            reserved.add(new ReservedItem(p.getId(), p.getSku(), p.getName(), p.getPrice(), quantity));
        }
        return reserved;
    }
}
