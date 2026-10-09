package com.shoplab.order.internal;

import com.shoplab.product.ProductInventory;
import com.shoplab.product.ProductUnavailableException;
import com.shoplab.product.ReservedItem;
import com.shoplab.user.UserDirectory;
import com.shoplab.user.UserSummary;
import com.shoplab.user.UserUnavailableException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Nghiệp vụ đơn hàng. Nhận command và trả entity: không biết gì về HTTP hay DTO web,
 * việc đổi sang JSON là của OrderController. Gọi module product chỉ qua API ProductInventory,
 * module user chỉ qua API UserDirectory.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepo;
    private final ProductInventory inventory;
    private final UserDirectory users;

    public OrderService(OrderRepository orderRepo, ProductInventory inventory, UserDirectory users) {
        this.orderRepo = orderRepo;
        this.inventory = inventory;
        this.users = users;
    }

    /**
     * Tạo đơn + giữ hàng trong 1 transaction.
     * Kiểm tra người đặt trước (không khoá gì), rồi mới giữ hàng: người dùng không hợp lệ thì không khoá sản phẩm.
     * Khoá sản phẩm, kiểm tra và trừ kho là việc của module product (ProductInventory.reserveStock);
     * khoá được giữ tới khi transaction này commit nên 2 đơn đồng thời không bán vượt tồn kho.
     * @return order vừa tạo, kèm các dòng hàng
     */
    @Transactional
    public Order create(CreateOrderCommand command) {
        UserSummary customer;
        try {
            customer = users.requireActiveUser(command.userId());
        } catch (UserUnavailableException ex) {
            // Với API đặt hàng, người dùng không tồn tại / bị khoá nghĩa là đơn không hợp lệ (422 invalid-order)
            throw new InvalidOrderException(ex.getMessage());
        }

        List<ReservedItem> reserved;
        try {
            reserved = inventory.reserveStock(command.quantities());
        } catch (ProductUnavailableException ex) {
            // Với API đặt hàng, sản phẩm không tồn tại / ngừng bán nghĩa là đơn không hợp lệ (422 invalid-order)
            throw new InvalidOrderException(ex.getMessage());
        }

        Order order = new Order(customer);
        reserved.forEach(order::addItem);

        // flush ngay: các câu INSERT (gộp theo lô) chạy tại đây, lỗi DB nếu có sẽ bay ra trong service
        return orderRepo.saveAndFlush(order);
    }

    /** @return order kèm các dòng hàng đã nạp sẵn (đọc được cả sau khi transaction kết thúc) */
    @Transactional(readOnly = true)
    public Order getById(Long id) {
        return orderRepo.findWithItemsById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    /** Đơn của một người dùng, không nạp dòng hàng (danh sách chỉ cần thông tin tóm tắt). */
    @Transactional(readOnly = true)
    public Page<Order> listByUser(long userId, Pageable pageable) {
        return orderRepo.findByUserId(userId, pageable);
    }

    /**
     * Đơn của một người dùng, kèm dòng hàng. Số câu SQL không phụ thuộc số đơn trong trang:
     * 1 câu lấy trang đơn (+1 câu đếm khi trang đầy) + 1 câu nạp dòng hàng của cả trang.
     * Để từng đơn tự nạp dòng hàng của nó (vd order.getItems().size() trong vòng lặp) là N+1:
     * trang 100 đơn thành 1 + 100 câu (scripts/bugs/13-n-plus-one.patch).
     * @return trang đơn, items đã nạp sẵn (đọc được cả sau khi transaction kết thúc)
     */
    @Transactional(readOnly = true)
    public Page<Order> listByUserWithItems(long userId, Pageable pageable) {
        Page<Order> page = orderRepo.findByUserId(userId, pageable);
        if (page.hasContent()) {
            orderRepo.fetchItems(page.map(Order::getId).getContent());
        }
        return page;
    }
}
