package com.flashorder.product.service;

import com.flashorder.common.exception.BusinessException;
import com.flashorder.product.domain.Product;
import com.flashorder.product.dto.*;
import com.flashorder.product.repository.ProductRepository;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductService {
    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) { this.productRepository = productRepository; }

    @Transactional
    public ProductResponse create(CreateProductRequest request) {
        return ProductResponse.from(productRepository.save(new Product(request.name(), request.price(), request.stock())));
    }

    public ProductResponse find(Long id) {
        return ProductResponse.from(productRepository.findById(id).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다.")));
    }

    public Page<ProductResponse> findAll(int page, int size) {
        return productRepository.findAll(PageRequest.of(page, size, Sort.by("id").descending())).map(ProductResponse::from);
    }
}
