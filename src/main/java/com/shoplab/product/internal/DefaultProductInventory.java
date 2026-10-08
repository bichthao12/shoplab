package com.shoplab.product.internal;

import com.shoplab.product.InsufficientStockException;
import com.shoplab.product.ProductInventory;
import com.shoplab.product.ProductUnavailableException;
import com.shoplab.product.ReservedItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cài đặt API ProductInventory: mỗi sản phẩm trừ kho bằng MỘT câu UPDATE có điều kiện.
 *
 * Kiểm tra còn hàng và trừ kho nằm trong cùng một câu lệnh ({@code WHERE ... stock >= :quantity}), nên không có
 * khoảng hở nào giữa "đọc" và "ghi" như bản ngây thơ. Khi nhiều đơn cùng trừ một sản phẩm, PostgreSQL cho từng
 * câu UPDATE lần lượt khoá dòng; câu đến sau chờ câu trước commit rồi kiểm tra lại điều kiện WHERE trên con số
 * mới nhất. Không còn đủ hàng thì câu UPDATE không cập nhật dòng nào, và đơn không được tạo.
 *
 * Câu UPDATE chạy thẳng xuống DB, không qua entity Product: nếu cùng transaction đã nạp Product trước đó thì
 * entity đó vẫn giữ số tồn kho cũ. Entity đó có bị sửa và lưu lại cũng không ghi đè được kho, vì cột stock
 * không nằm trong câu UPDATE của entity (updatable = false). Câu UPDATE ở đây không tăng version: version là của
 * thông tin sản phẩm (sku, tên, giá...), nên đơn hàng không làm PATCH sản phẩm của admin bị 409.
 */
@Service
class DefaultProductInventory implements ProductInventory {

    private final JdbcClient jdbc;

    DefaultProductInventory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ReservedItem> reserveStock(Map<Long, Integer> quantities) {
        List<ReservedItem> reserved = new ArrayList<>();
        // Theo thứ tự productId: mọi đơn khoá các dòng sản phẩm theo cùng một thứ tự → không deadlock
        for (Map.Entry<Long, Integer> line : new TreeMap<>(quantities).entrySet()) {
            long productId = line.getKey();
            int quantity = line.getValue();
            Assert.isTrue(quantity > 0, () -> "quantity phải > 0, nhận được " + quantity);

            List<ReservedItem> updated = jdbc.sql("""
                            UPDATE products
                            SET stock = stock - :quantity, updated_at = now()
                            WHERE id = :id AND active AND stock >= :quantity
                            RETURNING id, sku, name, price
                            """)
                    .param("id", productId)
                    .param("quantity", quantity)
                    .query((rs, i) -> new ReservedItem(
                            rs.getLong("id"), rs.getString("sku"), rs.getString("name"), rs.getBigDecimal("price"),
                            quantity))
                    .list();

            // Chỉ nhận khi cập nhật được đúng 1 dòng. 0 dòng: không tồn tại, ngừng bán hoặc không đủ hàng →
            // ném lỗi, transaction của đơn rollback (kể cả phần đã trừ của các sản phẩm trước), không đơn nào được tạo.
            if (updated.size() != 1) {
                throw whyNotReserved(productId, quantity);
            }
            reserved.add(updated.getFirst());
        }
        return reserved;
    }

    /** Câu UPDATE không trừ được: đọc lại sản phẩm để báo đúng lý do. */
    private RuntimeException whyNotReserved(long productId, int quantity) {
        return jdbc.sql("SELECT sku, active, stock FROM products WHERE id = :id")
                .param("id", productId)
                .query((rs, i) -> rs.getBoolean("active")
                        ? new InsufficientStockException(rs.getString("sku"), quantity, rs.getInt("stock"))
                        : new ProductUnavailableException("Sản phẩm " + rs.getString("sku") + " đang ngừng bán"))
                .optional()
                .orElseGet(() -> new ProductUnavailableException("Sản phẩm id = " + productId + " không tồn tại"));
    }
}
