# FlashOrder

고트래픽 선착순 주문 처리 과정을 단계별로 학습하는 프로젝트입니다.
V1에서 상품 등록·조회, 주문 생성·조회와 재고 차감을 구현했습니다.
현재 V2는 주문 시 PostgreSQL 비관적 락을 사용해 동시 주문의 재고 갱신 유실을 방지합니다.

## 기술 구성

Java 17 / Spring Boot 3.5.16 / Gradle Wrapper 9.7.1 / PostgreSQL 17
Spring Web, Data JPA, Bean Validation, Flyway / JUnit 5, MockMvc, H2, Testcontainers 2.0.5

## 프로젝트 구조

```text
src/main/java/com/flashorder/
├─ FlashOrderApplication.java
├─ product/
│  ├─ controller/ProductController.java
│  ├─ service/ProductService.java
│  ├─ repository/ProductRepository.java
│  ├─ domain/Product.java
│  └─ dto/{CreateProductRequest,ProductResponse}.java
├─ order/
│  ├─ controller/OrderController.java
│  ├─ service/OrderService.java
│  ├─ repository/OrderRepository.java
│  ├─ domain/Order.java
│  └─ dto/{CreateOrderRequest,OrderResponse}.java
└─ common/
   ├─ config/
   └─ exception/{BusinessException,GlobalExceptionHandler}.java
src/main/resources/
├─ application.yml
└─ db/migration/V1__create_products_and_orders.sql
src/test/java/com/flashorder/
├─ OrderIntegrationTest.java       # H2 기본 기능 테스트
└─ OrderConcurrencyTest.java       # 별도 PostgreSQL 컨테이너 동시성 테스트
docker/                         # 이후 컨테이너 설정
k6/order-test.js                 # 실제 HTTP 동시 주문 테스트
```

Controller는 입력 검증과 HTTP 응답, Service는 트랜잭션과 업무 흐름,
Repository는 DB 접근을 담당합니다. Entity 대신 DTO로 응답합니다.

## 로컬 실행

JDK 17과 Docker Compose v2가 필요합니다. IDE의 Gradle JVM도 JDK 17로 지정합니다.
Gradle 최초 실행 시 배포 파일과 의존성을 다운로드합니다.

```powershell
docker compose up -d --wait
.\gradlew.bat bootRun
```

macOS / Linux에서는 `sh ./gradlew bootRun`을 사용합니다.
기본 HTTP 포트는 8080이며 `/`에는 엔드포인트가 없습니다.
Flyway가 최초 실행 시 테이블을 생성하고 Hibernate가 매핑을 검증합니다.
기존 테이블이 있는 DB에 도입할 때는 기존 스키마와 마이그레이션을 먼저 비교해야 합니다.

빌드와 전체 테스트(Docker Desktop 실행 필요):

```powershell
.\gradlew.bat build
```

`OrderIntegrationTest`는 H2 인메모리 DB에서 Flyway 마이그레이션과 JPA 매핑을 실행합니다.
정상 주문, 금액 계산, 재고 부족, 입력 오류, 없는 상품·주문, 트랜잭션 롤백을 검증합니다.
`OrderConcurrencyTest`는 Testcontainers가 PostgreSQL 17 컨테이너를 생성하고
임의의 호스트 포트로 연결합니다. 개발용 Compose DB와 볼륨은 사용하지 않습니다.
테스트 종료 시 컨테이너를 제거하며, Docker가 꺼져 있으면 테스트는 생략되지 않고 실패합니다.
최초 실행 시 테스트 의존성과 PostgreSQL·테스트 관리용 이미지를 다운로드합니다.

테스트별 실행:

```powershell
# Docker 없이 기본 기능만 확인
.\gradlew.bat test --tests com.flashorder.OrderIntegrationTest

# Docker를 켜고 PostgreSQL 동시성 검증 (bootRun이나 compose up은 불필요)
.\gradlew.bat test --tests com.flashorder.OrderConcurrencyTest
```

동시성 테스트는 최대 20개 작업 스레드에서 각 요청을 별도 트랜잭션으로 실행합니다.
작업 시작을 맞춘 뒤, 모든 요청이 완료된 상태에서 응답 성공 수와 실제 DB 값을 비교합니다.
잠금 타임아웃이나 DB 오류를 재고 부족으로 처리하지 않으며, 해당 오류는 테스트를 실패시킵니다.

| 초기 재고 | 총 요청 | 요청당 수량 | 예상 성공 | 예상 재고 부족 | 잔여 재고 | 저장 수량 |
| --- | --- | --- | --- | --- | --- | --- |
| 10 | 100 | 1 | 10 | 90 | 0 | 10 |
| 10 | 30 | 3 | 3 | 27 | 1 | 9 |

롤백 테스트는 재고·주문 복원 후 다른 스레드의 주문도 성공하는지 확인해 잠금 해제를 검증합니다.
실행 결과는 `build/reports/tests/test/index.html`에서 확인합니다.

2026-11-05 검증: JDK 17에서 `gradlew.bat --no-daemon test` 실행 성공.
H2 기본 기능 4건과 실제 PostgreSQL 17 동시성·롤백 3건, 총 7건이 실패·생략 없이 통과했습니다.
이는 기능 검증 결과이며 k6 HTTP 성능 측정값은 아닙니다.

## API

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| POST | `/api/products` | 상품 등록, 201 및 Location 반환 |
| GET | `/api/products/{id}` | 상품 조회 |
| GET | `/api/products?page=0&size=20` | 상품 목록, ID 내림차순, 최대 100개 |
| POST | `/api/orders` | 주문 생성 및 재고 차감, 201 및 Location 반환 |
| GET | `/api/orders/{id}` | 주문 조회 |

상품 등록:

```http
POST /api/products
Content-Type: application/json

{"name":"키보드","price":15000.50,"stock":10}
```

상품명은 공백을 제외한 문자가 있어야 하며 최대 200자입니다.
가격은 양수, 정수부 최대 17자리·소수부 최대 2자리이며 재고는 0 이상 정수입니다.

주문 생성 (`productId`는 등록된 상품의 ID):

```http
POST /api/orders
Content-Type: application/json

{"productId":1,"quantity":2}
```

주문 수량은 1 이상 정수입니다. 주문 응답에는 `id`, `productId`, `quantity`,
`unitPrice`, `totalPrice`, `createdAt`이 포함됩니다.
주문 시점의 단가를 저장하며 총액은 해당 단가와 수량으로 계산합니다.

오류 응답 예시:

```json
{"code":"INSUFFICIENT_STOCK","message":"상품 재고가 부족합니다."}
```

| HTTP 상태 | 코드 | 의미 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 입력값 또는 JSON 형식 오류 |
| 404 | `PRODUCT_NOT_FOUND`, `ORDER_NOT_FOUND` | 대상 없음 |
| 409 | `INSUFFICIENT_STOCK` | 재고 부족 |

## 주문 처리와 동시성

재고 확인·차감과 주문 저장은 하나의 트랜잭션입니다. 중간 실패 시 모두 롤백됩니다.
V1은 일반 `findById()`로 상품을 조회하므로 여러 주문이 같은 재고를 읽고,
각자 계산한 값을 덮어써 재고 갱신 유실과 초과 판매가 발생할 수 있었습니다.
V2는 주문에만 `findByIdForUpdate()`를 사용합니다.
이 메서드의 `@Lock(PESSIMISTIC_WRITE)`가 PostgreSQL의 행 잠금 조회를 요청합니다.
일반 상품 조회 API는 기존 `findById()`를 사용하므로 주문용 잠금을 요청하지 않습니다.

```text
주문 A: 상품 행 잠금 획득 → 재고 검사·차감 → 주문 저장 → commit/rollback → 잠금 해제
주문 B: 같은 상품의 잠금 대기 → 잠금 획득 → 최신 재고로 검사
```

잠금 획득부터 주문 저장까지 기존 `OrderService.create()`의 같은 트랜잭션에서 수행합니다.
기본 READ COMMITTED 격리 수준에서 대기하던 주문은 앞선 트랜잭션이 끝난 뒤 최신 행을 읽습니다.
잠금은 flush가 아니라 트랜잭션 종료 시 해제됩니다.
관련 재고 변경 경로도 동일한 잠금 규칙을 따라야 하며, 트랜잭션 안에 외부 API 호출 등을 추가하면
잠금 유지 시간이 길어질 수 있습니다. 같은 상품의 주문은 직렬화되어 대기·처리량 한계가 생깁니다.
잠금 대기 제한과 재시도 정책은 현재 별도로 설정하지 않습니다.
락 타임아웃·DB 장애를 `INSUFFICIENT_STOCK`으로 변환하지 않으며, 자동 재시도도 하지 않습니다.

참고: [Spring Data JPA Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html),
[PostgreSQL 17 행 잠금](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS).

DB에도 양수 가격·수량, 음수가 아닌 재고, 상품 외래키 제약이 있습니다.
이 제약만으로 동시 주문의 초과 판매를 막을 수는 없습니다.

기존 Flyway 마이그레이션의 `version` 컬럼은 이미 적용된 DB와의 호환을 위해 남겨둡니다.
현재 엔티티에서는 매핑하지 않으며, 낙관적 잠금에 사용하지 않습니다.

인증, 결제, 주문 취소, 멱등성 키는 아직 없습니다.
동일한 요청을 반복하면 별도 주문이 만들어질 수 있습니다.
Redis, Kafka, Outbox, Prometheus/Grafana는 이후 단계에서 추가합니다.

## k6로 V1·V2 비교

Java 동시성 테스트는 Service를 직접 호출하며 HTTP 성능은 측정하지 않습니다.
실제 서버의 응답 시간은 기존 `k6/order-test.js`로 비교합니다.
각 버전에서 서버를 재시작하고, 다른 버전의 서버가 동시에 실행되지 않도록 합니다.
같은 PC, 사용자 수, 초기 재고, 요청 수량과 동일한 스크립트를 사용합니다.

1. Docker Compose의 PostgreSQL과 `bootRun`으로 해당 버전 서버를 실행합니다.
2. Postman의 `POST /api/products`로 매 실행마다 새 상품을 등록합니다.
   정확성 확인은 `{"name":"k6 재고 검증","price":10000,"stock":10}`을 사용합니다.
3. 등록된 상품 ID를 전달합니다.

   ```powershell
   k6 run -e PRODUCT_ID=실제상품ID .\k6\order-test.js
   ```

4. 100회 완료 여부, `created_orders`, `sold_out_orders`, 평균·p95·최대 응답 시간을 기록합니다.
   정상 조건에서 V2의 기대값은 주문 성공 10건, 재고 부족 90건입니다.
5. DBeaver에서 상품 ID를 바꿔 실제 저장 결과를 조회합니다.

   ```sql
   SELECT p.id, p.stock AS remaining_stock,
          COUNT(o.id) AS order_count,
          COALESCE(SUM(o.quantity), 0) AS ordered_quantity
   FROM products p
   LEFT JOIN orders o ON o.product_id = p.id
   WHERE p.id = 1
   GROUP BY p.id, p.stock;
   ```

기존 주문이 없는 새 상품이라면 `초기 재고 = 잔여 재고 + 저장된 총 주문 수량`이어야 합니다.
k6의 `checks`와 `http_req_failed`는 예상된 201·409 응답 여부를 나타내며 재고 정확성을 보장하지 않습니다.
처리량 비교는 별도로 새 상품의 재고를 100개 이상 준비해 같은 요청 100건을 모두 처리하게 합니다.
재고 부족 응답이 대부분인 실행과 전체 주문 저장을 수행한 실행을 동일한 성능 조건으로 비교하지 않습니다.
로컬 PC에서 k6와 서버·DB를 함께 실행하면 자원을 공유하므로 절대적인 운영 성능으로 해석하지 않습니다.

V1에서 관찰한 k6 결과는 성공 96건, 재고 부족 4건, 평균 237.32ms, p95 379.83ms였습니다.
해당 실행의 상품 ID·초기 재고·DB 집계는 기록되지 않았으므로 성능 비교의 확정 기준으로 사용하지 않습니다.
V2의 실제 HTTP 측정값은 재실행 후 기록해야 하며, 아래 표는 기록용입니다.

| 버전 | 상품 ID·초기 재고 | 완료 요청 수 | 성공·재고 부족 | 잔여 재고·저장 수량 | 평균·p95·최대 |
| --- | --- | --- | --- | --- | --- |
| V1 재측정 | 미측정 | 미측정 | 미측정 | 미측정 | 미측정 |
| V2 | 미측정 | 미측정 | 미측정 | 미측정 | 미측정 |

## DB 설정

| 항목 | 기본값 | 환경변수 |
| --- | --- | --- |
| 호스트 | `localhost` | `DB_HOST` (애플리케이션) |
| 포트 | `5432` | `DB_PORT` |
| 데이터베이스 | `flashorder` | `DB_NAME` |
| 사용자 | `flashorder` | `DB_USERNAME` |
| 비밀번호 | `flashorder` | `DB_PASSWORD` |

기본 계정은 로컬 개발용이며 PostgreSQL 포트는 로컬 호스트에만 공개합니다.
포트 변경 예시:

```powershell
$env:DB_PORT = '5433'
docker compose up -d --wait
.\gradlew.bat bootRun
```

Compose는 `.env`를 읽지만 Spring Boot는 자동으로 읽지 않습니다.
변경값은 같은 셸의 환경변수나 IDE 실행 환경변수에 설정합니다.
기존 볼륨의 DB 이름·계정은 Compose 환경변수 변경만으로 갱신되지 않습니다.
`open-in-view: false`로 웹 계층에서 지연 로딩하지 않습니다.

DB 종료 (데이터 보존): `docker compose down`

DB 데이터까지 초기화할 때만 `docker compose down -v`를 사용합니다.
