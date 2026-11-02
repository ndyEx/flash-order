package com.flashorder.order.dto;

import jakarta.validation.constraints.*;

public record CreateOrderRequest(
        @NotNull @Positive Long productId,
        @NotNull @Min(1) Integer quantity) {
}
