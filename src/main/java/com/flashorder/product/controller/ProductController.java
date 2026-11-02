package com.flashorder.product.controller;

import com.flashorder.product.dto.*;
import com.flashorder.product.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@Validated
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) { this.productService = productService; }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        ProductResponse productResponse = productService.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + productResponse.id())).body(productResponse);
    }

    @GetMapping("/{id}")
    public ProductResponse find(@PathVariable @Positive Long id) { return productService.find(id); }

    @GetMapping
    public Page<ProductResponse> findAll(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return productService.findAll(page, size);
    }
}
