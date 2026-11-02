package com.flashorder.order.domain;

import com.flashorder.product.domain.Product;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Order() {}

    public static Order place(Product product, int quantity) {
        product.decreaseStock(quantity);
        Order order = new Order();
        order.product = product;
        order.quantity = quantity;
        order.unitPrice = product.getPrice();
        order.createdAt = Instant.now();
        return order;
    }

    public Long getId() { return id; }
    public Product getProduct() { return product; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getTotalPrice() { return unitPrice.multiply(BigDecimal.valueOf(quantity)); }
    public Instant getCreatedAt() { return createdAt; }
}
