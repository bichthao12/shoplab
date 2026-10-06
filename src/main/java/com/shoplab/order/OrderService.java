package com.shoplab.order;

import com.shoplab.order.dto.CreateOrderRequest;
import com.shoplab.order.dto.OrderResponse;
import com.shoplab.product.Product;
import com.shoplab.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepo;
    private final ProductRepository productRepo;

    public OrderService(OrderRepository orderRepo, ProductRepository productRepo) {
        this.orderRepo = orderRepo;
        this.productRepo = productRepo;
    }

    /**
     * Tạo đơn + trừ tồn kho trong 1 transaction.
     * Khoá các dòng product (SELECT ... FOR UPDATE) để 2 đơn đồng thời không bán vượt tồn kho.
     * @return order vừa tạo (dạng response)
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest req) {
        Map<Long, Integer> wanted = req.mergedItems();

        Map<Long, Product> products = productRepo.findAllByIdInForUpdate(wanted.keySet()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Order order = new Order(
                req.customerName().trim(),
                req.customerEmail().trim().toLowerCase(Locale.ROOT));

        for (Map.Entry<Long, Integer> line : wanted.entrySet()) {
            Long productId = line.getKey();
            int quantity = line.getValue();

            Product p = products.get(productId);
            if (p == null) {
                throw new InvalidOrderException("Sản phẩm id = " + productId + " không tồn tại");
            }
            if (!p.isActive()) {
                throw new InvalidOrderException("Sản phẩm " + p.getSku() + " đang ngừng bán");
            }
            if (p.getStock() < quantity) {
                throw new InsufficientStockException(p.getSku(), quantity, p.getStock());
            }

            p.setStock(p.getStock() - quantity);
            order.addItem(p, quantity);
        }

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
