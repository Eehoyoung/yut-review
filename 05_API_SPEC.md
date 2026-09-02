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
  "tier": "TIER_2",
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

`POST`는 `SYSTEM_ADMIN`만 사용할 수 있으며 매장, 소유 멤버십, QR, 3개 상품을 함께 생성하고 최초 직원 PIN을 응답에서 한 번만 반환한다.

## 상품 조회/수정
```http
GET /api/admin/stores/{storeId}/prizes
PUT /api/admin/stores/{storeId}/prizes/{tier}
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
