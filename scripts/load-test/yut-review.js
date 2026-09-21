/*
 * 윷리뷰 부하 테스트. k6 전용 (https://k6.io, 단일 실행 파일, 무료, 계정 불필요).
 *
 * 계획과 합격 기준은 docs/LOAD_TEST_PLAN.md 에 있다. 이 파일은 그 계획의 시나리오를 그대로 옮긴 것이고,
 * 숫자를 바꾸려면 문서와 함께 바꾼다.
 *
 *   k6 run -e BASE=https://staging.example -e TOKENS=./tokens.json -e SCENARIO=smoke    scripts/load-test/yut-review.js
 *   k6 run -e BASE=... -e TOKENS=... -e SCENARIO=capacity scripts/load-test/yut-review.js
 *   k6 run -e BASE=... -e TOKENS=... -e SCENARIO=quota    scripts/load-test/yut-review.js
 *   k6 run -e BASE=... -e TOKENS=... -e SCENARIO=soak     scripts/load-test/yut-review.js
 *
 * 절대 운영(yut.sodamlabs.kr)에 대고 돌리지 말 것. 실제 매장의 일일 상한을 태우고 실제 쿠폰을 발급한다.
 */
import http from "k6/http";
import { check, sleep, fail } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE = __ENV.BASE || fail("BASE가 필요합니다 (예: -e BASE=https://staging.example)");
const SCENARIO = __ENV.SCENARIO || "smoke";

/** 승인된 매장의 QR 토큰 목록. 만드는 방법은 LOAD_TEST_PLAN.md의 '준비' 절에 있다. */
const tokens = JSON.parse(open(__ENV.TOKENS || "./tokens.json")).storeTokens;
if (!tokens || tokens.length === 0) fail("tokens.json 의 storeTokens 가 비어 있습니다");

const throttled = new Counter("throttled_429");
const gameCreate = new Trend("game_create_ms", true);

/**
 * VU마다 다른 전화번호.
 *
 * 2일 쿨타임이 참여 식별자를 (매장, 전화번호)로 잡기 때문에 번호를 돌려 쓰면 두 번째 요청부터
 * 전부 COOLDOWN으로 떨어진다. 그러면 측정하는 것이 게임 생성이 아니라 거절 경로가 된다.
 * 실행마다 접두 숫자를 바꿔야 어제 돌린 부하 테스트의 쿨타임에 걸리지 않는다.
 */
const RUN = __ENV.RUN_ID || String(Date.now()).slice(-3);
function phone() {
  const unique = String(__VU * 100000 + __ITER).padStart(5, "0").slice(-5);
  return `010${RUN}${unique}`;
}

const CONSENT = { privacyAgreed: true, privacyConsentVersion: __ENV.PRIVACY_VERSION || "2026-09-04" };
const JSON_HEADERS = { "Content-Type": "application/json" };

/** 한 손님의 정상 흐름. QR → 정보 입력 → 던지기 → 쿠폰. */
function customerFlow(storeToken) {
  const name = `부하${__VU}`;
  const p = phone();

  const intro = http.get(`${BASE}/api/public/stores/by-token/${storeToken}`);
  check(intro, { "매장 조회 200": (r) => r.status === 200 });

  const state = http.post(
    `${BASE}/api/public/stores/${storeToken}/customer-state`,
    JSON.stringify({ name, phone: p, ...CONSENT }),
    { headers: JSON_HEADERS },
  );
  if (state.status === 429) { throttled.add(1, { step: "state" }); return; }
  check(state, { "상태 조회 200": (r) => r.status === 200 });

  const body = state.json("data") || {};
  // 이미 쿠폰이 있으면 회수 티켓 경로를 태운다. 실제 재방문 손님이 겪는 흐름이다.
  if (body.state === "HAS_ACTIVE_COUPON" && body.recoveryTicket) {
    const recovered = http.post(
      `${BASE}/api/public/stores/${storeToken}/coupons/recover`,
      JSON.stringify({ ticket: body.recoveryTicket }),
      { headers: JSON_HEADERS },
    );
    check(recovered, { "쿠폰 회수 200": (r) => r.status === 200 });
    return;
  }
  if (body.state !== "CAN_PLAY") return;

  const created = http.post(
    `${BASE}/api/public/games`,
    JSON.stringify({
      storeToken,
      name,
      phone: p,
      idempotencyKey: `load-${RUN}-${__VU}-${__ITER}`,
      ...CONSENT,
    }),
    { headers: JSON_HEADERS },
  );
  gameCreate.add(created.timings.duration);
  if (created.status === 429) {
    throttled.add(1, { step: "game", code: created.json("error.code") });
    return;
  }
  if (!check(created, { "게임 생성 200": (r) => r.status === 200 })) return;

  // 3D 시뮬레이션이 도는 시간. 이 간격이 없으면 실제로는 일어나지 않는 밀집도를 만든다.
  sleep(3);

  const playId = created.json("data.playId");
  const revealed = http.post(`${BASE}/api/public/games/${playId}/reveal`, null, { headers: JSON_HEADERS });
  if (!check(revealed, { "reveal 200": (r) => r.status === 200 })) return;

  const couponToken = revealed.json("data.couponToken");
  check(http.get(`${BASE}/api/public/coupons/${couponToken}`), { "쿠폰 조회 200": (r) => r.status === 200 });
}

export default function () {
  if (SCENARIO === "quota") {
    // 매장 0번을 의도적으로 밀어붙이는 동안, 매장 1번의 손님은 아무 영향도 받지 않아야 한다.
    // 그것이 "매장 쿼터와 고객 경험을 분리한다"는 규칙의 실제 합격 조건이다.
    customerFlow(__VU % 4 === 0 ? tokens[1 % tokens.length] : tokens[0]);
  } else {
    customerFlow(tokens[(__VU + __ITER) % tokens.length]);
  }
  sleep(Math.random() * 2);
}

const SCENARIOS = {
  /** 배포가 살아 있는지. 부하가 아니라 경로 확인이다. */
  smoke: { executor: "constant-vus", vus: 2, duration: "1m" },
  /** 용량. 한도를 올려 둔 staging에서 순수 처리량과 지연을 잰다. */
  capacity: {
    executor: "ramping-vus",
    startVUs: 5,
    stages: [
      { duration: "2m", target: 25 },
      { duration: "5m", target: 50 },
      { duration: "3m", target: 100 },
      { duration: "2m", target: 0 },
    ],
  },
  /** 한도. 기본 한도 그대로 두고 429가 경계에서 나는지, 정상 손님이 말려드는지를 본다. */
  quota: { executor: "constant-arrival-rate", rate: 120, timeUnit: "1m", duration: "5m", preAllocatedVUs: 40 },
  /** 장시간. 카운터 정리, 메모리, 디스크 증가를 본다. */
  soak: { executor: "constant-vus", vus: 10, duration: "2h" },
};

export const options = {
  scenarios: { [SCENARIO]: SCENARIOS[SCENARIO] || fail(`알 수 없는 SCENARIO: ${SCENARIO}`) },
  thresholds: {
    // 5xx는 한 건도 허용하지 않는다. 429는 설계된 응답이라 실패가 아니다.
    "http_req_failed{expected_response:true}": ["rate<0.01"],
    game_create_ms: ["p(95)<800", "p(99)<2000"],
    checks: ["rate>0.98"],
  },
};
