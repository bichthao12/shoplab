package com.shoplab.product;

/**
 * Module nào còn lưu tham chiếu tới sản phẩm (vd dòng đơn hàng giữ productId) thì cài đặt interface này,
 * để module product không xoá sản phẩm đang được dùng. DB không có khoá ngoại chéo module nên đây là
 * chỗ duy nhất giữ ràng buộc đó.
 *
 * Module product chỉ định nghĩa interface, không phụ thuộc vào module cài đặt: Spring đưa mọi bean cài đặt
 * vào ProductService, nên phụ thuộc vẫn một chiều (order → product).
 */
public interface ProductReferences {

    /**
     * true nếu module này còn dữ liệu tham chiếu tới sản phẩm.
     * Được gọi trong transaction xoá sản phẩm, khi dòng sản phẩm đã bị khoá (FOR UPDATE): việc tạo dữ liệu
     * mới tham chiếu tới sản phẩm (vd giữ hàng cho đơn) cũng khoá dòng này, nên không chen vào giữa được.
     */
    boolean isReferenced(long productId);

    /** Loại dữ liệu đang tham chiếu, dùng trong thông báo lỗi, vd "đơn hàng". */
    String referencedBy();
}
