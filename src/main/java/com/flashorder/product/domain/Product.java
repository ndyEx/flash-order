package com.flashorder.product.domain;

import com.flashorder.common.exception.BusinessException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int stock;

    protected Product() {}

    public Product(String name, BigDecimal price, int stock) {
        if (name == null || name.isBlank() || name.length() > 200
                || price == null || price.signum() <= 0 || price.scale() > 2
                || price.precision() - price.scale() > 17 || stock < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT", "상품 정보를 확인해주세요.");
        }
        this.name = name.strip();
        this.price = price;
        this.stock = stock;
    }

    public void decreaseStock(int quantity) {
        if (quantity <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "수량은 1 이상이어야 합니다.");
        }
        if (stock < quantity) {
            throw new BusinessException(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", "상품 재고가 부족합니다.");
        }
        stock -= quantity;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public BigDecimal getPrice() { return price; }
    public int getStock() { return stock; }
}
