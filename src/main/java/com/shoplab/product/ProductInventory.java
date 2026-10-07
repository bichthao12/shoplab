package com.shoplab.product;

import java.util.List;
import java.util.Map;

/**
 * API của module product cho module khác: giữ hàng (khoá và trừ kho) cho một đơn.
 */
public interface ProductInventory {

    /**
     * Khoá các sản phẩm, kiểm tra rồi trừ kho, xử lý theo thứ tự productId.
     * Bắt buộc chạy trong transaction của bên gọi: khoá được giữ tới khi bên gọi commit/rollback,
     * nên đơn hàng và việc trừ kho cùng thành công hoặc cùng huỷ.
     *
     * @param quantities productId → số lượng cần giữ
     * @return thông tin sản phẩm tại thời điểm giữ hàng (sku, tên, giá) để chụp vào đơn
     * @throws ProductUnavailableException sản phẩm không tồn tại hoặc đang ngừng bán
     * @throws InsufficientStockException  không đủ hàng
     */
    List<ReservedItem> reserveStock(Map<Long, Integer> quantities);
}
