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
  "storeToken": "qR7...",
  "posterReady": true
}
```
가입과 동시에 `STORE_ADMIN` 계정, 매장, OWNER 멤버십, QR 토큰, 기본 3등급 상품과
기본 가중치 설정이 생성된다. `staffPin`은 이 응답에서 한 번만 반환한다.

`phone`은 숫자만 남겨 `010` + 8자리, `businessNumber`는 숫자 10자리여야 한다.
`010-1234-5678`이나 `123-45-67890`처럼 구분자가 섞여 있어도 서버가 숫자만 남겨 정규화한다.
자릿수가 맞지 않으면 잘라내지 않고 `INVALID_PHONE` / `INVALID_BUSINESS_NUMBER`로 거부한다.

## 로그인
```http
POST /api/admin/auth/login
```
Request:
```json
{ "email": "owner@example.com", "password": "secret1234" }
```
계정은 이메일로만 식별한다. 아이디(`loginId`) 개념은 없다. 대소문자는 서버가 소문자로 맞춘다.

## 내 매장
```http
GET /api/admin/stores
POST /api/admin/stores
GET /api/admin/stores/{storeId}
PUT /api/admin/stores/{storeId}
```

`POST`는 로그인한 관리자가 자기 매장을 하나 더 만드는 요청이다. 매장, 소유 멤버십, QR,
기본 3등급 상품과 기본 가중치 설정을 함께 생성하고 최초 직원 PIN을 응답에서 한 번만 반환한다.

Request:
```json
{ "name": "홍대포차 2호점", "businessNumber": "1234567891", "phone": "01012345678" }
```

`businessNumber`는 매장마다 유일해야 하며 중복이면 `DUPLICATE_BUSINESS_NUMBER`다.
한 계정이 가질 수 있는 매장 수를 넘기면 `STORE_LIMIT_REACHED`를 반환한다.

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

매장 생성과 회원가입은 요청의 현재 공개 origin으로 A6 비율 PNG 안내물을 서버 DB에 함께 저장한다.
매장명 변경과 QR 토큰 재발급은 저장된 안내물도 갱신한다.

## 매장 QR 안내물
```http
GET  /api/admin/stores/{storeId}/poster
POST /api/admin/stores/{storeId}/poster/regenerate
```

`GET /poster`는 서버에 저장된 PNG와 함께 `X-Poster-Public-Origin` 응답 헤더를 반환한다. 관리 화면은 이 값으로 실제 저장본의 QR 주소를 표시하고 현재 접속 origin과 다르면 재생성을 안내한다.

- `GET`은 인증된 매장 관리자에게 `image/png` 첨부 파일을 반환한다.
- `POST`는 현재 공개 origin, 현재 매장명, 활성 QR 토큰으로 서버 저장본을 다시 만든다.
- 기존 매장에 저장본이 없으면 첫 `GET`에서 한 번 생성한다.
- Quick Tunnel 주소가 바뀐 세션에서는 `POST` 후 새 안내물을 내려받아야 한다.

## 직원 PIN 재발급
```http
POST /api/admin/stores/{storeId}/staff-pin/regenerate
```

## 참여 내역
```http
GET /api/admin/stores/{storeId}/game-plays?page=0&size=50
```

`page`는 0부터 시작하고 `size` 기본값은 50, 허용 범위는 1~100이다. `playedAt DESC`로 DB에서 페이지 조회한다.

## 쿠폰 내역
```http
GET /api/admin/stores/{storeId}/coupons?page=0&size=50
```

`page`는 0부터 시작하고 `size` 기본값은 50, 허용 범위는 1~100이다. `issuedAt DESC`로 DB에서 페이지 조회한다.

두 목록의 `data` 형식:

```json
{
  "content": [],
  "page": 0,
  "size": 50,
  "totalElements": 0,
  "totalPages": 0
}
```

## 요금제 조회/변경
```http
GET /api/admin/stores/{storeId}/subscription
PUT /api/admin/stores/{storeId}/subscription
GET /api/admin/stores/{storeId}/subscription/plans
```
Request(PUT):
```json
{ "plan": "STANDARD", "note": "" }
```

구독 행이 없는 매장은 `BASIC`으로 응답한다. 결제(PG) 연동은 범위 밖이라 등급 변경은 운영자
조작으로만 일어난다.

**`PUT /api/admin/stores/{storeId}/subscription`은 닫혀 있다.** 누가 호출하든 403
`OPERATOR_CONSOLE_REQUIRED`이며, 유일한 변경 경로는 `PUT /api/system/stores/{storeId}/plan`이다.
콘솔 경로만 콘솔 권한 등급·재인증·세션 상태·감사 기록을 전부 지나기 때문이다. 같은 일을 하는 문이
둘이면 약한 쪽이 곧 그 기능의 보안 수준이 된다.

`analyticsRetentionDays`는 **비식별 집계**에만 적용된다. 고객 개인정보 보존은 요금제와 무관하게
120일 기준을 유지한다.

## AI
```http
GET  /api/admin/stores/{storeId}/ai/status
POST /api/admin/stores/{storeId}/ai/event-copy
POST /api/admin/stores/{storeId}/ai/report?from=&to=
GET  /api/admin/stores/{storeId}/ai/report/latest
POST /api/admin/stores/{storeId}/ai/improvement?from=&to=
GET  /api/admin/stores/{storeId}/ai/improvement/latest
POST /api/admin/stores/{storeId}/ai/chat
```

모든 요청이 인증 → 매장 멤버십 → 요금제 권한 → 월 한도 순으로 검사된다.

| code | 상황 |
|---|---|
| `PLAN_UPGRADE_REQUIRED` | 402. 현재 요금제에 없는 기능 |
| `AI_QUOTA_EXCEEDED` | 429. 이번 달 한도 소진 |
| `AI_PROVIDER_UNAVAILABLE` | 503. 공급자 오류·타임아웃 |
| `AI_RESPONSE_INVALID` | 503. 응답이 스키마와 맞지 않음 |
| `AI_TIMEOUT` | 503. 제한 시간 초과. 이미 과금됐을 수 있어 한도를 되돌리지 않는다 |
| `AI_NOT_CONFIGURED` | 503. 공급자 설정 미완료 |
| `PERSONAL_DATA_NOT_ALLOWED` | 400. 자유 입력에 전화번호·이메일이 있음 |
| `ANALYTICS_OUT_OF_RETENTION` | 400. 요금제 보관기간을 벗어난 조회 |

## 상세 분석과 CSV
```http
GET /api/admin/stores/{storeId}/analytics/detailed?from=&to=
GET /api/admin/stores/{storeId}/analytics/export/{daily|prize}?from=&to=
```

둘 다 STANDARD 이상이며, 없으면 402 `PLAN_UPGRADE_REQUIRED`다. CSV는 집계만 담고 참여자 명단을
내려주지 않는다. 시간대는 매장 시간(Asia/Seoul) 기준으로 집계한다.

`PUT /api/admin/stores/{storeId}`의 `posterTagline`은 브랜딩 권한(STANDARD 이상)이 필요하다.
권한 없이 값을 바꾸려 하면 무시가 아니라 402로 거부한다.

고객 API에는 AI 엔드포인트가 없다. 공급자 장애가 QR·게임·쿠폰 흐름에 전파되지 않아야 한다.

모델에 전달되는 값은 집계와 공개 라벨(매장명, 공개 상품명)뿐이다. 고객 이름·전화번호·phoneHash·
phoneLast4·쿠폰 토큰·직원 PIN은 어떤 기능에서도 전달되지 않는다.

## 통계
```http
GET /api/admin/stores/{storeId}/analytics/summary
```

# System Console API

운영자(SYSTEM_ADMIN) 전용이다. 주소부터 `/api/system`으로 나눠 두어 앞단(Nginx, 프록시)에서 이
경로만 따로 다룰 수 있다. 응답에는 고객 개인정보가 없고 매장 단위 집계까지만 담긴다.

막는 층은 다섯이다.

1. 꺼짐 스위치와 전역 IP 허용 목록. 막히면 401/403이 아니라 **404**로 답한다(존재 자체를 알리지 않는다).
2. 비밀번호 + TOTP(또는 복구 코드). 실패는 IP당 5분에 5회·계정당 10회로 제한하고, 연속 10회 실패면
   계정이 15분 잠긴다(잠금은 DB에 남아 재시작으로 풀리지 않는다).
3. 전용 스코프(`scope=OPERATOR`) 토큰 + 서버가 들고 있는 세션 행. 로그아웃·강제 종료·유휴 만료가
   토큰 만료를 기다리지 않고 바로 듣는다.
4. 요청마다 역할·계정 상태·세션 상태 재확인. 권한을 회수하거나 계정을 중지하면 즉시 막힌다.
5. 권한 등급(`VIEWER` < `OPERATOR` < `OWNER`)과 위험한 조작 앞의 재인증(step-up, 기본 5분).

## 운영자 로그인
```http
POST /api/system/auth/login
POST /api/system/auth/totp/confirm
```
Request(login):
```json
{ "email": "ops@example.com", "password": "", "code": "123456", "backupCode": "" }
```
`code`(인증 앱) 또는 `backupCode`(복구 코드) 중 하나를 쓴다.

Response(2단계 인증이 아직 없는 계정):
```json
{
  "status": "TOTP_ENROLLMENT_REQUIRED",
  "enrollmentToken": "",
  "secret": "BASE32",
  "otpauthUrl": "otpauth://totp/...",
  "qrImage": "data:image/png;base64,..."
}
```
Response(등록을 마친 계정):
```json
{
  "status": "AUTHENTICATED", "accessToken": "", "tokenType": "Bearer", "expiresInSeconds": 3600,
  "email": "", "name": "", "consoleRole": "OWNER", "mustChangePassword": false, "backupCodesRemaining": 10
}
```
`POST /api/system/auth/totp/confirm`은 `{ "enrollmentToken": "", "code": "123456" }`를 받아 등록을
확정하고 **복구 코드 10개를 한 번만** 돌려준다(`{ "status": "TOTP_ENROLLED", "backupCodes": [] }`).
등록에 쓴 코드는 이미 쓴 코드라 그대로 로그인되지 않는다(같은 30초 코드 재사용 차단).

없는 계정·틀린 비밀번호·운영자가 아닌 계정은 모두 같은 `AUTH_INVALID`를 받는다. 잠금·중지·IP 차단은
비밀번호가 맞은 뒤에만 알려 준다.

## 내 계정과 세션
```http
GET    /api/system/me
POST   /api/system/session/step-up     { "code": "123456" }
POST   /api/system/session/logout
GET    /api/system/sessions?scope=mine|all
DELETE /api/system/sessions/{sessionId}
POST   /api/system/account/password    { "currentPassword": "", "newPassword": "", "newPasswordConfirm": "" }
POST   /api/system/account/backup-codes
```

- `scope=all`과 남의 세션 종료는 `OWNER`이고 재인증을 거쳐야 한다.
- 비밀번호를 바꾸면 **다른 세션은 모두 닫힌다.** 운영자 비밀번호는 영문·숫자·기호를 포함해 12자 이상,
  이메일과 겹칠 수 없다.
- 임시 비밀번호(남이 만들어 준 계정)를 바꾸기 전에는 `/me`와 비밀번호 변경 외 모든 요청이
  403 `PASSWORD_CHANGE_REQUIRED`다.
- 복구 코드 재발급은 재인증이 필요하고, 발급하면 예전 코드는 모두 무효가 된다.

## 플랫폼 조회 (`VIEWER` 이상)
```http
GET /api/system/overview
GET /api/system/stores?page=&size=&query=
GET /api/system/stores/{storeId}
GET /api/system/audit?page=&size=&action=&actor=&failuresOnly=&from=&to=
```

`overview.security`는 열려 있는 세션 수, 24시간 실패 시도, 잠긴 계정, 2단계 인증 미등록 운영자 수를
함께 담는다. 감사 조회는 기간을 비우면 최근 30일을 본다.

## 매장 변경 (`OPERATOR` 이상 + 재인증)
```http
PUT /api/system/stores/{storeId}/plan     { "plan": "PRO", "note": "사유" }
PUT /api/system/stores/{storeId}/status   { "status": "INACTIVE", "note": "사유" }
```

`note`는 필수다. 기록에 "무엇을"만 남고 "왜"가 없으면 나중에 쓸모가 없다. 매장 중지는 손님의 QR
진입을 막을 뿐, 이미 발급된 쿠폰을 회수하지 않는다.

운영자가 매장 안의 설정(상품, 확률, 직원 PIN, 참여자 명단)을 대신 바꾸는 엔드포인트는 없다.

## 운영자 계정 (`OWNER` + 재인증)
```http
GET  /api/system/operators
POST /api/system/operators                  { "email": "", "name": "", "password": "", "consoleRole": "VIEWER" }
PUT  /api/system/operators/{adminId}        { "consoleRole": "", "disabled": false, "allowedIps": "" }
POST /api/system/operators/{adminId}/totp/reset
POST /api/system/operators/{adminId}/unlock
```

- 새 계정은 임시 비밀번호로 만들어지고 본인이 바꾸기 전에는 조회조차 못 한다.
- 마지막 `OWNER`는 권한을 내리거나 중지할 수 없고, 자기 자신의 권한도 내릴 수 없다(스스로 잠기는 사고).
- 권한을 내리거나 계정을 중지하면 그 계정의 세션이 즉시 닫힌다.
- `allowedIps`는 계정별 접속 허용 목록(IP 또는 CIDR, 쉼표 구분)이며 전역 설정과 함께 적용된다.

## 감사 기록 (`OPERATOR`: 내보내기 / `OWNER`: 검증)
```http
GET /api/system/audit/export?action=&actor=&failuresOnly=&from=&to=   (text/csv)
GET /api/system/audit/verify
```

로그인 성공·실패, 재인증, 세션 종료, 비밀번호 변경, 요금제·매장 상태 변경, 운영자 계정 변경, 권한
없는 시도, 기록 내보내기가 모두 남는다. 각 행은 앞 행의 해시를 품고 있어 `verify`가 중간이 바뀐
지점을 짚어 준다(마지막 행부터 잘라내는 것까지 잡지는 못한다. 조용히 고칠 수 없게 하는 장치다).

| code | 상황 |
|---|---|
| `TOTP_REQUIRED` | 401. 2단계 인증 코드가 비어 있음 |
| `TOTP_INVALID` | 401. 코드 불일치·이미 사용한 코드 |
| `TOTP_ALREADY_ENROLLED` | 400. 이미 등록된 계정의 확정 요청 |
| `BACKUP_CODE_INVALID` | 401. 복구 코드 불일치·이미 사용 |
| `ENROLLMENT_EXPIRED` | 401. 등록용 임시 토큰 만료(5분) |
| `ACCOUNT_LOCKED` | 423. 연속 실패로 잠긴 계정 |
| `ACCOUNT_DISABLED` | 403. 중지된 계정 |
| `IP_NOT_ALLOWED` | 403. 계정별 허용 IP 밖 |
| `SESSION_EXPIRED` | 401. 로그아웃·유휴 만료·기기 불일치·강제 종료 |
| `STEP_UP_REQUIRED` | 403. 재인증 필요 |
| `PASSWORD_CHANGE_REQUIRED` | 403. 임시 비밀번호를 아직 안 바꿈 |
| `PASSWORD_REUSED` | 400. 지금 쓰는 비밀번호와 같음 |
| `LAST_OWNER` | 400. 마지막 OWNER 권한 회수 시도 |
| `INVALID_IP_RULE` | 400. 허용 IP 형식 오류 |
| `OPERATOR_CONSOLE_REQUIRED` | 403. 운영자 콘솔 토큰이 아님 |
| `FORBIDDEN` | 403. 권한 등급 부족 |
| `NOT_FOUND` | 404. 콘솔이 꺼져 있거나 허용되지 않은 IP |

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
OPERATOR_CONSOLE_REQUIRED
TOTP_REQUIRED
TOTP_INVALID
TOTP_ALREADY_ENROLLED
BACKUP_CODE_INVALID
ENROLLMENT_EXPIRED
ACCOUNT_LOCKED
ACCOUNT_DISABLED
IP_NOT_ALLOWED
SESSION_EXPIRED
STEP_UP_REQUIRED
PASSWORD_CHANGE_REQUIRED
PASSWORD_REUSED
LAST_OWNER
INVALID_IP_RULE
```
