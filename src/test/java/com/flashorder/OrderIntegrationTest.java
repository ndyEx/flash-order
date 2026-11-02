package com.flashorder;

import com.flashorder.order.dto.CreateOrderRequest;
import com.flashorder.order.repository.OrderRepository;
import com.flashorder.order.service.OrderService;
import com.flashorder.product.dto.CreateProductRequest;
import com.flashorder.product.repository.ProductRepository;
import com.flashorder.product.service.ProductService;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orders;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password="})
@AutoConfigureMockMvc
class OrderIntegrationTest {
    @Autowired ProductService productService;
    @Autowired OrderService orderService;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;

    @BeforeEach
    void clean() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
    }

    long product(int stock) {
        return productService.create(new CreateProductRequest("Keyboard", new BigDecimal("15000.50"), stock)).id();
    }

    @Test
    void orderPersistsPriceAndDecreasesStock() throws Exception {
        long id = product(5);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + id + ",\"quantity\":2}"))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.totalPrice").value(30001.00));
        assertThat(productService.find(id).stock()).isEqualTo(3);
        long orderId = orderRepository.findAll().get(0).getId();
        mvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(2));
    }

    @Test
    void insufficientStockLeavesNoOrderOrStockChange() throws Exception {
        long id = product(1);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":" + id + ",\"quantity\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value("상품 재고가 부족합니다."));
        assertThat(productService.find(id).stock()).isEqualTo(1);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void validatesRequestsAndMissingResources() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Mouse\",\"price\":1000,\"stock\":3}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.stock").value(3));
        mvc.perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\" \",\"price\":-1,\"stock\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1,\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":1,\"quantity\":1.5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/products?size=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/999999")).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":999999,\"quantity\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void failureAfterStockDecreaseRollsBackOrderAndStock() {
        long id = product(5);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            orderService.create(new CreateOrderRequest(id, 2));
            throw new IllegalStateException("Simulated transaction failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(productService.find(id).stock()).isEqualTo(5);
        assertThat(orderRepository.count()).isZero();
    }

}
