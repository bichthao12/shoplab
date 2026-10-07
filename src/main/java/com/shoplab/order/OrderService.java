package com.shoplab.order;

import com.shoplab.product.ProductService;
import com.shoplab.product.ProductUnavailableException;
import com.shoplab.product.ReservedItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Nghiệp vụ đơn hàng. Nhận command và trả entity: không biết gì về HTTP hay DTO web,
 * việc đổi sang JSON là của OrderController.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepo;
    private final ProductService productService;

    public OrderService(OrderRepository orderRepo, ProductService productService) {
        this.orderRepo = orderRepo;
        this.productService = productService;
    }

    /**
     * Tạo đơn + giữ hàng trong 1 transaction.
     * Khoá sản phẩm, kiểm tra và trừ kho là việc của module product (ProductService.reserveStock);
     * khoá được giữ tới khi transaction này commit nên 2 đơn đồng thời không bán vượt tồn kho.
     * @return order vừa tạo, kèm các dòng hàng
     */
    @Transactional
    public Order create(CreateOrderCommand command) {
        List<ReservedItem> reserved;
        try {
            reserved = productService.reserveStock(command.quantities());
        } catch (ProductUnavailableException ex) {
            // Với API đặt hàng, sản phẩm không tồn tại / ngừng bán nghĩa là đơn không hợp lệ (422 invalid-order)
            throw new InvalidOrderException(ex.getMessage());
        }

        Order order = new Order(command.customerName(), command.customerEmail());
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
}
