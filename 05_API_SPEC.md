# API SPECIFICATION

Base URL: `/api`

## 공통 응답
성공:
```json
{
  "success": true,
  "data": {},
  "error": null
}
```

실패:
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "PARTICIPATION_COOLDOWN",
    "message": "9월 4일부터 다시 참여하실 수 있습니다."
  }
}
```

# Public API

## 매장 조회
```http
GET /api/public/stores/by-token/{storeToken}
```
Response:
```json
{
  "name": "홍대포차",
  "naverPlaceUrl": "https://naver.me/xxxx",
  "prizes": [
    { "rank": 1, "name": "삼겹살 1인분", "description": "1테이블 1회", "odds": 10.0 },
    { "rank": 2, "name": "계란찜 무료", "description": "", "odds": 25.0 },
    { "rank": 3, "name": "음료 1캔", "description": "", "odds": 65.0 }
  ]
}
```

`odds`는 그 등급으로 이어지는 결과들의 가중치 합을 전체 합으로 나눈 백분율이며
소수 첫째 자리에서 반올림한다. 서버에서만 계산한다.
비활성 상품과, 가중치가 모두 0이라 도달할 수 없는 등급은 목록에서 제외한다.
나올 수 없는 상품을 확률과 함께 노출하지 않기 위한 규칙이다.

## 고객 상태 조회
```http
POST /api/public/stores/{storeToken}/customer-state
```
Request:
```json
{
  "name": "홍길동",
  "phone": "01012345678",
  "privacyAgreed": true
}
```

Response 상태:
```text
HAS_ACTIVE_COUPON
CAN_PLAY
COOLDOWN
```

예:
```json
{
  "state": "COOLDOWN",
  "nextPlayableDate": "2026-09-04"
}
```

## 게임 생성
```http
POST /api/public/games
```
Request:
```json
{
  "storeToken": "qR7...",
  "name": "홍길동",
  "phone": "01012345678",
  "idempotencyKey": "uuid"
}
```
Response:
```json
{
  "playId": "01J...",
  "animationSeed": "seed_xxx",
  "animationProfile": "STANDARD"
}
```
당첨 결과는 이 응답에서 노출하지 않는다.

## 결과 공개
```http
POST /api/public/games/{playId}/reveal
```
Response:
```json
{
  "playId": "01J...",
  "yutResult": "YUT",
  "prizeRank": 2,
  "couponToken": "cp_xxx",
  "prize": {
    "name": "계란찜 무료",
    "description": "1테이블 1회"
  },
  "validFrom": "2026-09-02T19:30:00+09:00",
  "expiresAt": "2026-12-01T23:59:59+09:00"
}
```
Reveal은 멱등성을 보장한다.

## 쿠폰 조회
```http
GET /api/public/coupons/{couponToken}
```

## 쿠폰 사용
```http
POST /api/public/coupons/{couponToken}/redeem
```
Request:
```json
{ "pin": "483921" }
```
서버 검증:
- status == ISSUED
- now >= validFrom
- now <= expiresAt
- 입력 PIN이 Coupon의 Store PIN과 일치

# Admin API

## 매장 회원가입
```http
POST /api/admin/auth/signup
```
Request:
```json
{
  "ownerName": "홍대표",
  "phone": "01012345678",
  "loginId": "hongstore",
  "password": "secret1234",
  "passwordConfirm": "secret1234",
  "email": "owner@example.com",
  "storeName": "홍대포차",
  "businessNumber": "1234567890"
}
```
Response:
```json
{
  "storeId": 3,
  "storeName": "홍대포차",
  "staffPin": "483921",
  "storeToken": "qR7..."
}
```
가입과 동시에 `STORE_ADMIN` 계정, 매장, OWNER 멤버십, QR 토큰, 3개 상품이 생성된다.
`staffPin`은 이 응답에서 한 번만 반환한다.

## 로그인
```http
POST /api/admin/auth/login
```
Request:
```json
{ "loginId": "hongstore", "password": "secret1234" }
```
`loginId`에는 아이디 또는 이메일을 넣을 수 있다.

## 내 매장
```http
GET /api/admin/stores
POST /api/admin/stores
GET /api/admin/stores/{storeId}
PUT /api/admin/stores/{storeId}
```

`POST`는 `SYSTEM_ADMIN`만 사용할 수 있으며 매장, 소유 멤버십, QR, 기본 3등급 상품과
기본 가중치 설정을 함께 생성하고 최초 직원 PIN을 응답에서 한 번만 반환한다.

## 상품 조회/수정
```http
GET /api/admin/stores/{storeId}/prizes
PUT /api/admin/stores/{storeId}/prizes/{rank}
```
Request 예:
```json
{
  "name": "계란찜 무료",
  "description": "1테이블 1회",
  "redeemPolicy": "ANYTIME",
  "active": true
}
```

## 게임 설정 조회/저장
```http
GET /api/admin/stores/{storeId}/game-config
PUT /api/admin/stores/{storeId}/game-config
```
Response / Request:
```json
{
  "rankCount": 3,
  "outcomes": [
    { "yutResult": "DO",   "weight": 325, "prizeRank": 3, "odds": 32.5 },
    { "yutResult": "GAE",  "weight": 325, "prizeRank": 3, "odds": 32.5 },
    { "yutResult": "GEOL", "weight": 125, "prizeRank": 2, "odds": 12.5 },
    { "yutResult": "YUT",  "weight": 125, "prizeRank": 2, "odds": 12.5 },
    { "yutResult": "MO",   "weight": 100, "prizeRank": 1, "odds": 10.0 }
  ]
}
```

`odds`와 `rankCount`는 서버가 계산해 내려주는 읽기 전용 값이다. `PUT` 요청에
포함되어도 무시한다.

`PUT`은 5개 결과를 모두 담은 전체 문서를 한 트랜잭션으로 저장한다. 부분 저장은
확률 표가 반쯤 적용된 상태를 만들 수 있어 허용하지 않는다. 필요한 상품 등급이
없으면 함께 생성하고, 더 이상 쓰이지 않는 등급의 상품은 비활성화한다.

검증 실패는 모두 400이다.

| code | 조건 |
|---|---|
| `INVALID_WEIGHT` | 가중치가 0~1000 범위 밖 |
| `ZERO_WEIGHT_SUM` | 가중치 합이 0 |
| `INVALID_RANK_SEQUENCE` | 사용된 등급이 1..N 연속이 아니거나 N이 5 초과 |
| `INVALID_REQUEST` | 결과 5개가 모두 들어 있지 않음 |

이미 발급된 쿠폰은 설정 변경의 영향을 받지 않는다. 등급·상품명·설명·사용정책은
발급 시점에 쿠폰에 동결된다.

## QR 조회/재발급
```http
GET  /api/admin/stores/{storeId}/qr-codes
POST /api/admin/stores/{storeId}/qr-codes/regenerate
```

## 직원 PIN 재발급
```http
POST /api/admin/stores/{storeId}/staff-pin/regenerate
```

## 참여 내역
```http
GET /api/admin/stores/{storeId}/game-plays
```

## 쿠폰 내역
```http
GET /api/admin/stores/{storeId}/coupons
```

## 통계
```http
GET /api/admin/stores/{storeId}/analytics/summary
```

# Error Code
```text
STORE_NOT_FOUND
STORE_INACTIVE
QR_TOKEN_INVALID
QR_TOKEN_REVOKED
INVALID_PHONE
PRIVACY_CONSENT_REQUIRED
ACTIVE_COUPON_EXISTS
PARTICIPATION_COOLDOWN
STAFF_PIN_INVALID
STAFF_PIN_RATE_LIMITED
GAME_ALREADY_CREATED
GAME_NOT_FOUND
GAME_ALREADY_REVEALED
COUPON_NOT_FOUND
COUPON_NOT_ACTIVE
COUPON_NOT_YET_VALID
COUPON_EXPIRED
COUPON_ALREADY_REDEEMED
AUTH_INVALID
AUTH_RATE_LIMITED
INVALID_LOGIN_ID
PASSWORD_MISMATCH
WEAK_PASSWORD
INVALID_EMAIL
INVALID_BUSINESS_NUMBER
DUPLICATE_LOGIN_ID
DUPLICATE_EMAIL
DUPLICATE_BUSINESS_NUMBER
```
