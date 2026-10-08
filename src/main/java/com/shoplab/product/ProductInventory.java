package com.shoplab.product;

import java.util.List;
import java.util.Map;

/**
 * API của module product cho module khác: giữ hàng (khoá và trừ kho) cho một đơn.
 */
public interface ProductInventory {

    /**
     * Trừ kho từng sản phẩm theo thứ tự productId, mỗi sản phẩm bằng một câu UPDATE có điều kiện
     * (chỉ trừ khi đang bán và còn đủ hàng). Sản phẩm nào không trừ được thì ném lỗi, không trả về gì.
     * Bắt buộc chạy trong transaction của bên gọi: dòng sản phẩm bị khoá tới khi bên gọi commit/rollback,
     * nên đơn hàng và việc trừ kho cùng thành công hoặc cùng huỷ.
     *
     * @param quantities productId → số lượng cần giữ
     * @return thông tin sản phẩm tại thời điểm giữ hàng (sku, tên, giá) để chụp vào đơn
     * @throws ProductUnavailableException sản phẩm không tồn tại hoặc đang ngừng bán
     * @throws InsufficientStockException  không đủ hàng
     */
    List<ReservedItem> reserveStock(Map<Long, Integer> quantities);
}
