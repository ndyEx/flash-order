// PowerShell 실행 예시: k6 run -e PRODUCT_ID=1 .\k6\order-test.js
// PRODUCT_ID는 미리 등록한 테스트 상품의 ID로 바꿉니다.
// 반복 실행할 때는 새 상품을 등록해 초기 재고를 동일하게 맞춥니다.
import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

// 실행 결과에 표시할 사용자 정의 지표: 주문 성공 건수와 재고 부족 건수입니다.
const createdOrders = new Counter("created_orders");
const soldOutOrders = new Counter("sold_out_orders");

export const options = {
    scenarios: {
        orders: {
            // 가상 사용자(VU)마다 지정한 횟수만큼 default 함수를 실행합니다.
            executor: "per-vu-iterations",
            // 100명이 각각 1회 실행하므로 주문 요청은 총 100건입니다.
            // 실제 요청 도착 시점은 조금씩 다를 수 있습니다.
            vus: 100,
            iterations: 1,
            // 시나리오 실행 제한 시간입니다. 완료 횟수는 결과에서 확인합니다.
            maxDuration: "1m",
        },
    },
    thresholds: {
        // 아래 check가 전부 통과해야 테스트를 성공으로 판정합니다.
        // 응답 종류만 검사하는 조건이며, 재고 정확성까지 보장하지는 않습니다.
        checks: ["rate==1"],
    },
};

// 이 테스트에서는 201(생성 성공)과 409(충돌)를 예상된 HTTP 상태로 취급합니다.
// 따라서 409는 http_req_failed 지표에서 실패로 집계하지 않습니다.
// 409가 실제로 재고 부족 응답인지는 아래에서 오류 코드까지 검사합니다.
http.setResponseCallback(http.expectedStatuses(201, 409));

export default function () {
    // 가상 사용자 한 명이 실행하는 주문 요청입니다.
    const response = http.post(
        "http://localhost:8080/api/orders",
        // JavaScript 객체를 HTTP 요청 본문에 넣을 JSON 문자열로 변환합니다.
        JSON.stringify({
            // -e PRODUCT_ID=1로 전달한 문자열 환경변수를 숫자로 변환합니다.
            productId: Number(__ENV.PRODUCT_ID),
            // 모든 요청이 같은 상품을 1개씩 주문합니다.
            quantity: 1,
        }),
        {
            // 서버가 요청 본문을 JSON으로 해석하도록 지정합니다.
            headers: { "Content-Type": "application/json" },
        },
    );

    // 정상 생성과 재고 부족을 구분합니다. 다른 상태나 오류 코드는 검사 실패입니다.
    const created = response.status === 201;
    const soldOut =
        response.status === 409 &&
        response.json("code") === "INSUFFICIENT_STOCK";

    // 각 요청의 결과를 해당 카운터에 1건씩 누적합니다.
    if (created) createdOrders.add(1);
    if (soldOut) soldOutOrders.add(1);

    // 요청 한 건의 응답을 검사합니다. 검사 실패만으로 실행을 즉시 중단하지는 않습니다.
    // 최종 성공 여부는 options.thresholds에서 판단합니다.
    check(response, {
        "주문 성공 또는 재고 부족": () => created || soldOut,
    });
}

// 테스트 후 DB에서도 검증해야 합니다.
// 기존 주문이 없는 새 상품이라면: 초기 재고 = 남은 재고 + 저장된 총 주문 수량
// 초과 판매된 주문도 201을 반환할 수 있으므로 check 통과만으로 판단하지 않습니다.
