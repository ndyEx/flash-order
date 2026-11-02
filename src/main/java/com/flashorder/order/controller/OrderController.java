package com.flashorder.order.controller;

import com.flashorder.order.dto.*;
import com.flashorder.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse orderResponse = orderService.create(request);
        return ResponseEntity.created(URI.create("/api/orders/" + orderResponse.id())).body(orderResponse);
    }

    @GetMapping("/{id}")
    public OrderResponse find(@PathVariable @Positive Long id) {
        return orderService.find(id);
    }
}
