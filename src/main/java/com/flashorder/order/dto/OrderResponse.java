package com.flashorder.order.dto;

import com.flashorder.order.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(Long id, Long productId, int quantity, BigDecimal unitPrice,
        BigDecimal totalPrice, Instant createdAt) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getProduct().getId(), order.getQuantity(),
                order.getUnitPrice(), order.getTotalPrice(), order.getCreatedAt());
    }
}
