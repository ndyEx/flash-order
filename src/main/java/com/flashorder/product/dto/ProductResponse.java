package com.flashorder.product.dto;

import com.flashorder.product.domain.Product;
import java.math.BigDecimal;

public record ProductResponse(Long id, String name, BigDecimal price, int stock) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getPrice(), product.getStock());
    }
}
