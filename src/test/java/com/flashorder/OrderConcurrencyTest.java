package com.flashorder;

import com.flashorder.common.exception.BusinessException;
import com.flashorder.order.dto.CreateOrderRequest;
import com.flashorder.order.repository.OrderRepository;
import com.flashorder.order.service.OrderService;
import com.flashorder.product.dto.CreateProductRequest;
import com.flashorder.product.repository.ProductRepository;
import com.flashorder.product.service.ProductService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 개발 DB가 아닌 별도 PostgreSQL 17 컨테이너를 생성하고, 테스트 종료 시 제거합니다.
// 테스트에 @Transactional을 붙이지 않습니다. 각 스레드의 Service 호출이 독립적으로 commit합니다.
@SpringBootTest
@Testcontainers
@Timeout(90)
class OrderConcurrencyTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("flashorder_concurrency_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ProductService productService;
    @Autowired OrderService orderService;
    @Autowired ProductRepository productRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTestDatabase() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
    }

    @ParameterizedTest
    @CsvSource({
            "10, 100, 1, 10, 0",
            "10, 30, 3, 3, 1"
    })
    void concurrentOrdersPreserveStock(int initialStock, int requestCount, int quantity,
            int expectedSuccesses, int expectedRemainingStock) throws Exception {
        long productId = createProduct(initialStock);
        int workerCount = Math.min(20, requestCount);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("주문 시작 대기 시간 초과");
                    }
                    try {
                        orderService.create(new CreateOrderRequest(productId, quantity));
                        return true;
                    } catch (BusinessException error) {
                        // 재고 부족만 예상된 실패입니다. DB 연결·잠금 오류 등은 테스트를 실패시킵니다.
                        if (!"INSUFFICIENT_STOCK".equals(error.getCode())) {
                            throw error;
                        }
                        return false;
                    }
                }));
            }

            // 큐의 모든 요청이 아니라, 먼저 실행되는 작업 스레드들이 준비될 때까지 기다립니다.
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int successes = 0;
            int insufficientStock = 0;
            for (Future<Boolean> result : results) {
                if (result.get(60, TimeUnit.SECONDS)) {
                    successes++;
                } else {
                    insufficientStock++;
                }
            }

            int remainingStock = productService.find(productId).stock();
            long orderedQuantity = jdbcTemplate.queryForObject(
                    "select coalesce(sum(quantity), 0) from orders where product_id = ?",
                    Long.class, productId);

            assertThat(successes).isEqualTo(expectedSuccesses);
            assertThat(insufficientStock).isEqualTo(requestCount - expectedSuccesses);
            assertThat(orderRepository.count()).isEqualTo(expectedSuccesses);
            assertThat(remainingStock).isEqualTo(expectedRemainingStock);
            assertThat(orderedQuantity).isEqualTo((long) expectedSuccesses * quantity);
            assertThat(remainingStock + orderedQuantity).isEqualTo(initialStock);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rollbackRestoresStockAndReleasesLock() throws Exception {
        long productId = createProduct(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            orderService.create(new CreateOrderRequest(productId, 1));
            throw new IllegalStateException("주문 저장 후 강제 롤백");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(productService.find(productId).stock()).isEqualTo(1);
        assertThat(orderRepository.count()).isZero();

        // 다른 스레드의 새 트랜잭션도 잠금을 얻고 주문할 수 있어야 합니다.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> orderService.create(new CreateOrderRequest(productId, 1)))
                    .get(10, TimeUnit.SECONDS);
            assertThat(productService.find(productId).stock()).isZero();
            assertThat(orderRepository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long createProduct(int stock) {
        return productService.create(
                new CreateProductRequest("동시 주문 테스트 상품", new BigDecimal("10000"), stock)).id();
    }
}
