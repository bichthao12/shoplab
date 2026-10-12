package com.shoplab.order.internal;

import com.shoplab.product.ProductInventory;
import com.shoplab.product.ProductUnavailableException;
import com.shoplab.product.ReservedItem;
import com.shoplab.user.UserDirectory;
import com.shoplab.user.UserSummary;
import com.shoplab.user.UserUnavailableException;
import org.springframework.data.domain.Limit;
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

    /**
     * Một trang danh sách đơn, mới nhất trước, phân trang cursor (keyset): trang sau bắt đầu ngay sau đơn cuối của
     * trang trước, nên trang thứ bao nhiêu cũng chỉ đọc chừng ấy dòng như trang đầu, và đơn mới đặt giữa hai lần gọi
     * không làm lặp hay sót đơn. Không đếm tổng số đơn (đếm 10 triệu đơn tốn hơn cả trang), không nhảy tới trang số N.
     * Không nạp dòng hàng (danh sách chỉ cần thông tin tóm tắt).
     * @param userId chỉ lấy đơn của người dùng này; null: mọi đơn
     * @param after  vị trí của đơn cuối trang trước (OrderSlice.next); null: trang đầu
     * @param size   số đơn mỗi trang
     */
    @Transactional(readOnly = true)
    public OrderSlice listOrders(Long userId, OrderPosition after, int size) {
        Limit limit = Limit.of(size + 1);   // lấy thừa một đơn: có đơn đó thì còn trang sau
        List<Order> rows;
        if (userId == null) {
            rows = after == null ? orderRepo.findNewest(limit)
                    : orderRepo.findNewestAfter(after.createdAt(), after.id(), limit);
        } else {
            rows = after == null ? orderRepo.findNewestByUserId(userId, limit)
                    : orderRepo.findNewestByUserIdAfter(userId, after.createdAt(), after.id(), limit);
        }
        if (rows.size() <= size) {
            return new OrderSlice(rows, null);
        }
        List<Order> page = rows.subList(0, size);
        return new OrderSlice(page, OrderPosition.of(page.getLast()));
    }

    /**
     * Đơn của một người dùng, kèm dòng hàng: 1 câu SQL lấy trang đơn cùng dòng hàng (+1 câu đếm khi trang đầy), trang
     * bao nhiêu đơn cũng vậy (OrderRepository.findWithItemsByUserId). Để từng đơn tự nạp dòng hàng của nó
     * (vd order.getItems().size() trong vòng lặp) là N+1: trang 100 đơn thành 1 + 100 câu
     * (scripts/bugs/13-n-plus-one.patch).
     * @return trang đơn, items đã nạp sẵn (đọc được cả sau khi transaction kết thúc)
     */
    @Transactional(readOnly = true)
    public Page<Order> listByUserWithItems(long userId, Pageable pageable) {
        return orderRepo.findWithItemsByUserId(userId, pageable);
    }
}
