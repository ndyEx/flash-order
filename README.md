# FlashOrder

고트래픽 선착순 주문 처리 과정을 단계별로 학습하는 프로젝트입니다.
현재 V1은 상품 등록·조회, 주문 생성·조회와 재고 차감까지 구현합니다.

## 기술 구성

Java 17 / Spring Boot 3.5.16 / Gradle Wrapper 9.7.1 / PostgreSQL 17
Spring Web, Data JPA, Bean Validation, Flyway / JUnit 5, MockMvc, H2

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
src/test/java/com/flashorder/OrderIntegrationTest.java
docker/                         # 이후 컨테이너 설정
k6/                             # 이후 부하 테스트
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

빌드와 테스트:

```powershell
.\gradlew.bat build
```

테스트는 별도의 H2 인메모리 DB에서 Flyway 마이그레이션과 JPA 매핑을 실행합니다.
정상 주문, 금액 계산, 재고 부족, 입력 오류, 없는 상품·주문,
트랜잭션 롤백을 검증합니다.
PostgreSQL 환경의 부하·동시성 검증은 별도로 진행해야 합니다.

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
V1에는 낙관적·비관적 잠금 등의 동시성 제어를 적용하지 않습니다.
여러 주문이 같은 재고를 동시에 읽으면 재고 갱신이 유실되거나 초과 판매가 발생할 수 있습니다.
트랜잭션만으로 이러한 문제를 막지는 못하며, 동시 요청의 재고 정확성을 보장하지 않습니다.
다음 단계에서 PostgreSQL을 사용해 동시성 문제를 재현한 뒤 DB Lock 등의 개선을 진행합니다.
DB에도 양수 가격·수량, 음수가 아닌 재고, 상품 외래키 제약이 있습니다.
이 제약만으로 동시 주문의 초과 판매를 막을 수는 없습니다.

기존 Flyway 마이그레이션의 `version` 컬럼은 이미 적용된 DB와의 호환을 위해 남겨둡니다.
현재 엔티티에서는 매핑하지 않으며, 낙관적 잠금에 사용하지 않습니다.

인증, 결제, 주문 취소, 멱등성 키는 아직 없습니다.
동일한 요청을 반복하면 별도 주문이 만들어질 수 있습니다.
Redis, Kafka, Outbox, k6, Prometheus/Grafana는 이후 단계에서 추가합니다.

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
