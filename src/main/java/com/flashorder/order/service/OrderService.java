package com.flashorder.order.service;

import com.flashorder.common.exception.BusinessException;
import com.flashorder.order.domain.Order;
import com.flashorder.order.dto.*;
import com.flashorder.order.repository.OrderRepository;
import com.flashorder.product.domain.Product;
import com.flashorder.product.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderService {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다."));
        Order order = Order.place(product, request.quantity());
        return OrderResponse.from(orderRepository.saveAndFlush(order));
    }

    public OrderResponse find(Long id) {
        return OrderResponse.from(orderRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.")));
    }
}
