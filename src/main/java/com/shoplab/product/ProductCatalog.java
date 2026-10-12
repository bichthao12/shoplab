package com.shoplab.product;

import java.util.Collection;
import java.util.List;

/**
 * API của module product cho module khác: đọc thông tin HIỆN TẠI của sản phẩm (giá, tồn kho, đang bán hay không).
 * Khác với ReservedItem: đó là thông tin chụp lúc giữ hàng, đơn hàng giữ nguyên dù sản phẩm đổi sau đó.
 */
public interface ProductCatalog {

    /**
     * @param ids id các sản phẩm cần đọc
     * @return sản phẩm có id trong danh sách, xếp theo id; id không tồn tại (sản phẩm đã xoá) thì bỏ qua
     */
    List<ProductInfo> findByIds(Collection<Long> ids);
}
