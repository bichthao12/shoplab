package com.shoplab.order;

import com.shoplab.order.dto.OrderResponse;
import com.shoplab.product.ProductService;
import com.shoplab.product.ProductUnavailableException;
import com.shoplab.product.ReservedItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
     * @return order vừa tạo (dạng response)
     */
    @Transactional
    public OrderResponse create(CreateOrderCommand command) {
        List<ReservedItem> reserved;
        try {
            reserved = productService.reserveStock(command.quantities());
        } catch (ProductUnavailableException ex) {
            // Với API đặt hàng, sản phẩm không tồn tại / ngừng bán nghĩa là đơn không hợp lệ (422 invalid-order)
            throw new InvalidOrderException(ex.getMessage());
        }

        Order order = new Order(command.customerName(), command.customerEmail());
        reserved.forEach(order::addItem);

        Order saved = orderRepo.saveAndFlush(order);   // flush để có id, createdAt
        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse getById(Long id) {
        return orderRepo.findWithItemsById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }
}
