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
  "posterTagline": "QR을 찍고 윷을 던져 보세요",
  "naverPlaceUrl": "https://naver.me/xxxx",
  "prizes": [
    { "rank": 1, "name": "삼겹살 1인분", "description": "1테이블 1회", "odds": 10.0 },
    { "rank": 2, "name": "계란찜 무료", "description": "", "odds": 25.0 },
    { "rank": 3, "name": "음료 1캔", "description": "", "odds": 65.0 }
  ]
}
```

`posterTagline`은 매장이 설정한 공개 이벤트 안내 문구다. 값이 없으면 빈 문자열이다.
`naverPlaceUrl`도 선택값이며 빈 문자열일 수 있다. 링크가 있어도 네이버 방문이나 리뷰 작성은
게임 참여와 혜택의 조건이 아니다.

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
  "privacyAgreed": true,
  "privacyConsentVersion": "2026-09-21"
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
  "state": "HAS_ACTIVE_COUPON",
  "nextPlayableDate": "",
  "recoveryTicket": "<opaque>"
}
```

```json
{
  "state": "COOLDOWN",
  "nextPlayableDate": "2026-09-04",
  "recoveryTicket": ""
}
```

이 응답은 `couponToken`을 반환하지 않는다. 예전에는 미사용 쿠폰이 있으면 여기서 바로 쿠폰 토큰을
내려줬는데, 전화번호 하나만 알면 남의 쿠폰 bearer token을 받아갈 수 있었기 때문에 제거했다.
대신 `recoveryTicket`을 주고, 실제 쿠폰은 아래 회수 API를 한 번 더 호출해야 받는다.

`recoveryTicket`은 `HAS_ACTIVE_COUPON`일 때만 비어 있지 않다. 5분 만료, 1회용이며
(매장 · phoneHash · couponId)에 묶여 있다. 서버는 티켓 해시만 저장하고 평문은 이 응답에서만 나간다.

## 쿠폰 회수
```http
POST /api/public/stores/{storeToken}/coupons/recover
```
Request:
```json
{ "ticket": "<recoveryTicket>" }
```
Response는 `GET /api/public/coupons/{couponToken}`과 같은 couponView다.
```json
{
  "prizeRank": 1,
  "couponToken": "cp_xxx",
  "status": "ISSUED",
  "prize": { "name": "삼겹살 1인분", "description": "1테이블 1회" },
  "redeemPolicy": "ANYTIME",
  "validFrom": "2026-09-22T19:30:00+09:00",
  "expiresAt": "2026-12-20T23:59:59+09:00"
}
```

티켓은 한 번 쓰면 소모된다. 만료·재사용·다른 매장 토큰·존재하지 않는 티켓은 전부 400
`RECOVERY_TICKET_INVALID` 하나로 응답하며 어느 쪽인지 구분해 알려주지 않는다.
어떤 전화번호에 쿠폰이 있는지 이 엔드포인트로 탐색할 수 없게 하기 위한 규칙이다.

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
  "idempotencyKey": "uuid",
  "privacyAgreed": true,
  "privacyConsentVersion": "2026-09-21"
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

서버는 현재 고객 개인정보 동의 버전을 검증하고 참여 기록에 동의 버전과 시각을 저장한다.
`customer-state`를 먼저 호출했더라도 게임 생성 요청에서 다시 검증하므로 직접 API 호출로 우회할 수 없다.

요청/성공 응답 형식은 그대로지만 429가 새로 날 수 있다. 매장·IP 분당 한도를 넘으면
`GAME_RATE_LIMITED`, 매장 1일 생성 상한을 넘으면 `STORE_DAILY_LIMIT`이다.
전화번호 기준 참여 제한은 여전히 2일 쿨타임과 미사용 쿠폰 우선 규칙이 authoritative하며,
rate limit이 이를 대체하지 않는다.

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
  "businessNumber": "1234567890",
  "termsAgreed": true,
  "termsVersion": "2026-09-21",
  "privacyAgreed": true,
  "privacyVersion": "2026-09-21"
}
```
Response:
```json
{
  "storeId": 3,
  "storeName": "홍대포차",
  "status": "PENDING_APPROVAL",
  "approvalRequired": true
}
```
가입과 동시에 `STORE_ADMIN` 계정, 매장, OWNER 멤버십, 기본 3등급 상품과 기본 가중치 설정이
생성된다. 매장은 `PENDING_APPROVAL`로 시작한다. 가입 자체는 성공이고 로그인도 되지만
**`staffPin`과 `storeToken`은 더 이상 이 응답에서 반환하지 않는다.** QR·직원 PIN·안내물은
운영자 승인 후에 열린다.

서버는 현재 이용약관과 개인정보 동의 버전을 각각 검증하고 동의 시각·버전을 계정에 저장한다.
동의가 없거나 구버전이면 `TERMS_CONSENT_REQUIRED` / `PRIVACY_CONSENT_REQUIRED`로 거부한다.

광고성 문자 선택값 `yutReviewMarketing`, `reviewPilotMarketing`, `sodamMarketing`은 모두 선택이며
생략 시 `false`다. 하나라도 동의하면 `marketingVersion`이 현재 동의문 버전이어야 한다. 가입 성공 여부나
서비스 권한에 영향을 주지 않고 서비스별 최초 선택 이력으로 저장한다.

## 광고성 문자 수신동의
```http
GET /api/admin/marketing-consents
```

인증한 관리자 본인의 `YUT_REVIEW`, `REVIEW_PILOT`, `SODAM` 최신 동의 상태를 반환한다.

```http
PUT /api/admin/marketing-consents
Content-Type: application/json

{
  "service": "YUT_REVIEW",
  "agreed": false,
  "version": "2026-09-21"
}
```

동의와 철회는 append-only 이벤트로 저장한다. 현재 버전이 아니면
`MARKETING_CONSENT_VERSION_INVALID`로 거부하며, 한 서비스의 선택은 다른 서비스 상태를 바꾸지 않는다.

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

## 내 계정
```http
GET /api/admin/me
```
Response:
```json
{ "id": 3, "email": "owner@example.com", "name": "홍대표", "role": "STORE_ADMIN" }
```

`role`은 `SYSTEM_ADMIN` 또는 `STORE_ADMIN`이다. 관리자 화면은 이 값으로만 운영자 메뉴 노출을
판단하고, JWT를 직접 해석하지 않는다.

## 내 매장
```http
GET /api/admin/stores
POST /api/admin/stores
GET /api/admin/stores/{storeId}
PUT /api/admin/stores/{storeId}
```

`GET`의 각 항목은 상태와 거부 사유를 포함한다.

```json
[
  { "id": 12, "name": "홍대포차", "businessNumber": "3582302207",
    "status": "PENDING_APPROVAL", "approvalNote": "" }
]
```

`status`는 `PENDING_APPROVAL` / `ACTIVE` / `INACTIVE` / `REJECTED` 네 가지다.
`approvalNote`는 상태가 `REJECTED`일 때 마지막 거부 사유가 채워지고, 그 밖에는 `""`다.
값은 `stores`가 아니라 `store_approval_events`의 마지막 `REJECT` 행에서 읽는다.

`POST`는 로그인한 관리자가 자기 매장을 하나 더 만드는 요청이다. 매장, 소유 멤버십,
기본 3등급 상품과 기본 가중치 설정을 함께 생성한다. 추가 매장도 `PENDING_APPROVAL`로 시작하며
**직원 PIN과 QR 토큰은 응답에 담기지 않는다.**

Request:
```json
{ "name": "홍대포차 2호점", "businessNumber": "1234567891", "phone": "01012345678" }
```

Response:
```json
{ "id": 13, "name": "홍대포차 2호점", "status": "PENDING_APPROVAL", "approvalRequired": true }
```

`businessNumber`는 매장마다 유일해야 하며 중복이면 `DUPLICATE_BUSINESS_NUMBER`다.
한 계정이 가질 수 있는 매장 수를 넘기면 `STORE_LIMIT_REACHED`를 반환한다.

## 승인 전 매장의 엔드포인트 차단

매장이 `ACTIVE`가 아니면 아래 운영 엔드포인트는 403이다. 코드는 상태에 따라
`STORE_PENDING_APPROVAL`, `STORE_REJECTED`, `STORE_INACTIVE` 중 하나다.

```text
PUT  /api/admin/stores/{storeId}
     /api/admin/stores/{storeId}/prizes
     /api/admin/stores/{storeId}/game-config
     /api/admin/stores/{storeId}/event-configuration
     /api/admin/stores/{storeId}/event-settings
     /api/admin/stores/{storeId}/qr-codes
     /api/admin/stores/{storeId}/poster
     /api/admin/stores/{storeId}/staff-pin
     /api/admin/stores/{storeId}/game-plays
     /api/admin/stores/{storeId}/coupons
     /api/admin/stores/{storeId}/analytics
     /api/admin/stores/{storeId}/ai/**
```

열려 있는 것은 계정 단위 엔드포인트(`GET /api/admin/me`, `GET /api/admin/stores`,
`POST /api/admin/stores`, `/api/admin/marketing-consents`)와 매장 요약
`GET /api/admin/stores/{storeId}`뿐이다. 요약은 점주가 자기 매장의 심사 상태를 확인하는 통로라
승인 전에도 허용한다.

## 운영자 API
```http
GET  /api/admin/operator/summary
GET  /api/admin/operator/monitoring
GET  /api/admin/operator/stores?status=&page=0&size=20
POST /api/admin/operator/stores/{storeId}/approve
POST /api/admin/operator/stores/{storeId}/reject
POST /api/admin/operator/stores/{storeId}/review-again
POST /api/admin/operator/stores/{storeId}/ownership
GET  /api/admin/operator/stores/{storeId}/approval-events
POST /api/admin/operator/phone-hash/rehash
GET  /api/admin/operator/admins?q=&page=0&size=20
POST /api/admin/operator/admins
POST /api/admin/operator/admins/{adminId}/grant
POST /api/admin/operator/admins/{adminId}/revoke
GET  /api/admin/operator/audit
GET    /api/admin/operator/access/status
GET    /api/admin/operator/access/devices
POST   /api/admin/operator/access/register-challenge
POST   /api/admin/operator/access/register
POST   /api/admin/operator/access/authenticate-challenge
POST   /api/admin/operator/access/authenticate
DELETE /api/admin/operator/access/devices/{deviceId}
```

### 계정 — `/api/admin/operator/admins`

목록은 `{id, email, name, role, storeCount, createdAt}`만 내려간다. `passwordHash`는 어떤 경로로도
나가지 않는다. `q`는 이메일과 이름을 대소문자 무시로 부분 일치시킨다.

`POST /admins`는 `{email, name, password, passwordConfirm, note?}`를 받아 `SYSTEM_ADMIN`을 만든다.
**매장은 만들지 않는다** — 운영자가 어느 매장의 멤버가 되면 자기 매장을 스스로 심사할 수 있다.
비밀번호 규칙은 일반 가입과 같다(영문+숫자 10자 이상, `WEAK_PASSWORD`/`PASSWORD_MISMATCH`).

`grant`/`revoke`는 `{note?}`를 받고 `{..., changed}`를 돌려준다. 이미 그 역할이면 `changed:false`이고
기록도 남기지 않는다(아무 일도 일어나지 않았기 때문이다).

회수는 두 경우에 막힌다.

| 코드 | 언제 |
|---|---|
| `OPERATOR_SELF_REVOKE` | 자기 자신의 운영자 권한을 회수하려 할 때 |
| `OPERATOR_LAST_ONE` | 남은 운영자가 한 명일 때 |

둘 다 승인할 사람이 아무도 없는 상태를 막는다. 그 상태가 되면 운영자를 다시 만드는 API도
운영자만 쓸 수 있어서 DB를 직접 고쳐야 한다.

### 활동 기록 — `GET /api/admin/operator/audit`

매장 심사(`store_approval_events`)와 계정 변경(`operator_audit_events`)을 시간순으로 합쳐
최근 200건까지 준다. 한 줄은 `{kind, action, actor, target, storeId?, note, createdAt}`이며
`kind`는 `STORE` 또는 `ACCOUNT`다. `target`은 매장이면 매장명, 계정이면 **사건 시점의 이메일**이다
(계정이 지워져도 누구였는지가 남아야 한다).

### 접근 통제 — `/api/admin/operator/access/**`

`OPERATOR_ACCESS_ENABLED=true`면 `/api/admin/operator/**` 전체가 문지기를 지난다.

| 상황 | 결과 |
|---|---|
| 클라이언트 IP가 `OPERATOR_ALLOWED_CIDRS` 안 | 통과 |
| 그 밖 + `X-Operator-Device` 헤더가 유효한 통행증 | 통과 |
| 그 밖 + 통행증 없음/만료 | 403 `DEVICE_REQUIRED` |

`status`, `authenticate-challenge`, `authenticate` 셋만 문지기를 지나지 않는다. 지나게 하면
기기 인증을 하려면 먼저 기기 인증을 통과해야 하는 순환이 생긴다. **`register`는 지난다** —
첫 기기는 허용 IP에서만 등록된다.

등록: `register-challenge`가 `{challenge, rpId, timeoutMs}`를 준다. 브라우저 `navigator.credentials
.create()`의 결과에서 `getPublicKey()`(SPKI DER)와 `getPublicKeyAlgorithm()`을 꺼내
`{name, credentialId, publicKey, algorithm, clientDataJson}`으로 보낸다. 서버는 attestation을
받지도 검증하지도 않는다.

인증: `authenticate-challenge`가 `{challenge, rpId, allowCredentials, timeoutMs}`를 준다.
`navigator.credentials.get()` 결과를 `{credentialId, clientDataJson, authenticatorData, signature}`로
보내면 `{deviceToken, expiresAt, deviceName}`이 온다. **`deviceToken` 평문은 이때 한 번만 내려간다**
(서버는 SHA-256만 저장한다). 수명 4시간.

서버가 검증하는 것: clientData의 `type`·`challenge`·`origin`, `rpIdHash`, User Present 비트,
서명, 서명 카운터. 챌린지는 1회용이라 쓰는 즉시 지운다. 실패는 이유를 구분하지 않고 전부
`DEVICE_ASSERTION_INVALID`(403)다.

| 코드 | 언제 |
|---|---|
| `DEVICE_REQUIRED` | 허용 IP 밖인데 통행증이 없다 |
| `DEVICE_ASSERTION_INVALID` | 기기 인증 실패 (이유는 구분하지 않는다) |
| `DEVICE_CHALLENGE_INVALID` | 등록 챌린지가 만료·불일치 |
| `DEVICE_ALREADY_REGISTERED` | 같은 자격증명이 이미 있다 |
| `DEVICE_KEY_INVALID` | 공개키를 SPKI로 읽을 수 없다 |
| `DEVICE_NOT_FOUND` | 남의 기기이거나 없는 기기 |

### 자원 현황 — `GET /api/admin/operator/monitoring`

공개 게임 생성의 자원 고갈 방어를 눈으로 보는 자리다. 집계와 매장 공개 라벨만 나가며 고객
개인정보는 한 칸도 들어 있지 않다.

```json
{
  "date": "2026-09-22",
  "throttled": { "GAME_RATE_LIMITED": 12, "STORE_DAILY_LIMIT": 0, "SIGNUP_RATE_LIMITED": 3,
                 "AUTH_RATE_LIMITED": 1, "STAFF_PIN_RATE_LIMITED": 0, "RATE_LIMITED": 5 },
  "counters": { "rows": 412, "maxRows": 50000 },
  "limits": { "gamePerStorePerMinute": 30, "gamePerIpPerMinute": 10, "gamePerStorePerDay": 2000 },
  "storage": { "gamePlays": 10234, "coupons": 10234, "recoverySessions": 3,
               "aiChatTurns": 40, "stores": 52, "posterBase64Chars": 8912340 },
  "busiestStores": [
    { "storeId": 3, "name": "테스트포차", "playsToday": 143, "dailyLimit": 2000, "usedPercent": 7.2 }
  ]
}
```

- `throttled`은 **최근 24시간** 거절 수이고, 0인 코드도 함께 내려온다(화면이 "조용함"과
  "집계 없음"을 구분해야 한다).
- 거절 기록은 거절을 만든 트랜잭션과 분리해 커밋된다. 같이 묶으면 거절 기록만 정확히 전부 사라진다.
- `busiestStores`는 오늘 참여가 많은 순 최대 10곳이다.
- 임계값 판단 기준은 `docs/LOAD_TEST_PLAN.md` 5장에 있다.

`/api/admin/operator/**`는 `SYSTEM_ADMIN` 전용이다. 일반 관리자가 호출하면 403 `OPERATOR_ONLY`다.
운영자는 어느 매장의 멤버도 아니므로 이 경로에서는 매장 membership 검사를 하지 않는다.

`GET /summary`:
```json
{ "pending": 3, "active": 12, "rejected": 1 }
```

`GET /stores`는 `status`를 생략하면 전체를 반환하고, `data`는 다른 목록과 같은 페이지 형식이다.
content 항목:
```json
{
  "id": 12,
  "name": "홍대포차",
  "businessNumber": "3582302207",
  "ownerName": "홍대표",
  "ownerEmail": "owner@example.com",
  "ownerPhone": "01012345678",
  "status": "PENDING_APPROVAL",
  "createdAt": "2026-09-22T10:00:00+09:00",
  "note": ""
}
```

| 엔드포인트 | Request | Response |
|---|---|---|
| `POST .../approve` | `{ "note": "선택, 200자" }` | `{ "id":12, "status":"ACTIVE", "changed":true }` |
| `POST .../reject` | `{ "reason": "필수, 1~200자" }` | `{ "id":12, "status":"REJECTED", "changed":true }` |
| `POST .../review-again` | `{ "note": "선택" }` | `{ "id":12, "status":"PENDING_APPROVAL", "changed":true }` |
| `POST .../ownership` | `{ "email":"new@owner.com", "note":"선택" }` | `{ "id":12, "ownerEmail":"new@owner.com" }` |

이미 그 상태면 `changed:false`로 응답하고 감사 로그를 추가하지 않는다. 같은 요청을 다시 눌러도
이력이 늘지 않게 하려는 것이다. `review-again`은 `REJECTED`에서만 가능하다.
`ownership`의 이메일에 해당하는 계정이 없으면 400 `ADMIN_NOT_FOUND`다.

`GET .../approval-events`는 append-only 감사 로그를 그대로 반환한다.
```json
[
  { "action": "REJECT", "actorEmail": "operator@example.com",
    "note": "사업자등록번호 확인 불가", "createdAt": "2026-09-22T11:00:00+09:00" }
]
```
`action`은 `APPROVE` / `REJECT` / `REVIEW_AGAIN` / `OWNERSHIP_CHANGE`다.

`POST /phone-hash/rehash`는 phone HMAC 키 회전 중 이전 키로 만든 해시를 현재 키로 재계산한다.
이전 키를 폐기하기 전에 한 번만 실행한다.
```json
{ "scanned": 1200, "rehashed": 340 }
```

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

## 상품·확률 원자 저장

```http
PUT /api/admin/stores/{storeId}/event-configuration
```

관리자 상품 설정 화면은 결과별 가중치와 사용 중인 모든 상품을 이 요청 하나로 저장한다.

Request:

```json
{
  "outcomes": [
    { "yutResult": "DO",   "weight": 325, "prizeRank": 3 },
    { "yutResult": "GAE",  "weight": 325, "prizeRank": 3 },
    { "yutResult": "GEOL", "weight": 125, "prizeRank": 2 },
    { "yutResult": "YUT",  "weight": 125, "prizeRank": 2 },
    { "yutResult": "MO",   "weight": 100, "prizeRank": 1 }
  ],
  "prizes": [
    { "rank": 1, "name": "삼겹살 1인분", "description": "1테이블 1회", "redeemPolicy": "ANYTIME" },
    { "rank": 2, "name": "계란찜 무료", "description": "", "redeemPolicy": "NEXT_DAY" },
    { "rank": 3, "name": "음료 1캔", "description": "", "redeemPolicy": "SAME_DAY" }
  ]
}
```

`outcomes`는 물리 결과 5개를 정확히 한 번씩 포함해야 한다. `prizes`는 결과에서 사용하는 연속 등급
`1..N`을 정확히 한 번씩 모두 포함한다. `active`는 클라이언트가 보내지 않는다. 문서에 포함된 상품은
활성화되고, 더 이상 사용하지 않는 기존 상위 등급은 삭제하지 않고 비활성화된다.

Response:

```json
{
  "rankCount": 3,
  "outcomes": [
    { "yutResult": "DO", "weight": 325, "prizeRank": 3, "odds": 32.5 }
  ],
  "prizes": [
    { "rank": 1, "name": "삼겹살 1인분", "description": "1테이블 1회", "redeemPolicy": "ANYTIME", "active": true }
  ]
}
```

서버는 결과와 상품 전체를 먼저 검증한 뒤 하나의 트랜잭션으로 반영한다. 검증 또는 저장 중 하나라도
실패하면 전부 롤백한다. 이미 발급된 쿠폰의 상품·등급·사용정책 스냅샷은 바뀌지 않는다.

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

쿠폰 목록의 각 행은 연결된 참여 기록의 `customerName`, `phoneLast4`와 쿠폰의 `token`, `prizeName`, `status`, `issuedAt`, `expiresAt`을 포함한다. 이름은 인증된 매장 관리자에게만 복호화해 제공한다.

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

구독 행이 없는 매장은 `BASIC`으로 응답한다. 결제(PG) 연동은 범위 밖이라 등급 변경은 관리자
조작으로만 일어난다.

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
POST   /api/admin/stores/{storeId}/ai/chat
GET    /api/admin/stores/{storeId}/ai/chat/history
DELETE /api/admin/stores/{storeId}/ai/chat/history
```

모든 요청이 인증 → 매장 멤버십 → 요금제 권한 → 월 한도 순으로 검사된다.

채팅 이력은 서버가 소유한다. `POST /ai/chat` 요청 body는 `{ "message": "..." }`뿐이며
**클라이언트가 보내던 `history`는 제거했다.** 응답은 그대로 `{ answer, toolsUsed }`다.
모델에 들어가는 이전 턴은 서버가 저장한 것만 쓰고, 저장 시점에 개인정보 필터를 통과한 텍스트다.

```http
GET /api/admin/stores/{storeId}/ai/chat/history
```
```json
{
  "turns": [
    { "role": "user", "content": "이번 달 이벤트 반응 어때?", "createdAt": "2026-09-22T10:00:00+09:00" },
    { "role": "assistant", "content": "...", "createdAt": "2026-09-22T10:00:04+09:00" }
  ]
}
```
오래된 턴이 먼저 오고 최대 12개다.

```http
DELETE /api/admin/stores/{storeId}/ai/chat/history
```
```json
{ "cleared": 8 }
```

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

응답에는 `todayPlays`, `totalPlays`, `issuedCoupons`, `redeemedCoupons`, `results`, `plan`,
`advancedAvailable`와 현재 요금제에서 허용된 CSV 종류인 `csvExports`가 포함된다.
상세 통계의 `prizePerformance.prizes`는 등급을 `prizeRank`로 반환한다.

# Rate Limit

DB 카운터로 세며 Redis를 쓰지 않는다. 한도를 넘으면 429다.

| 대상 | 키 | 한도 | code |
|---|---|---|---|
| 매장 회원가입 | IP | 1시간 5회 / 24시간 20회 | `SIGNUP_RATE_LIMITED` |
| 매장 회원가입 | 사업자등록번호 | 24시간 3회 | `SIGNUP_RATE_LIMITED` |
| 고객 상태 조회 | IP | 1분 60회 | `RATE_LIMITED` |
| 게임 생성 | storeId | 1분 30회 | `GAME_RATE_LIMITED` |
| 게임 생성 | IP | 1분 10회 | `GAME_RATE_LIMITED` |
| 게임 생성 | storeId 일일 행 | 1일 2000건 | `STORE_DAILY_LIMIT` |
| 로그인 | IP | 1분 5회, 성공 시 초기화 | `AUTH_RATE_LIMITED` |
| 로그인 | 이메일 | 1분 5회, 성공 시 초기화 | `AUTH_RATE_LIMITED` |
| 직원 PIN | storeId + IP | 1분 10회 | `STAFF_PIN_RATE_LIMITED` |
| 직원 PIN | storeId + couponId | 1분 5회 | `STAFF_PIN_RATE_LIMITED` |

고객 상태 조회에 한도가 붙은 이유는 그 호출이 회수 티켓 행을 만들기 때문이다. 읽기처럼 보이지만
쓰기가 있는 공개 엔드포인트다. 게임 생성(IP 분당 10)보다 훨씬 넉넉한 것은 한국 모바일 손님 상당수가
통신사 NAT 뒤라 한 IP에 여러 명이 뭉치기 때문이다. 처음 분당 20으로 두고 부하 테스트를 돌렸더니
정상 흐름이 막혔다. `app.limits.state-lookup-per-ip-per-minute`로 조정한다.

게임 생성의 세 한도는 `app.limits.game-per-store-per-minute` / `-per-ip-per-minute` /
`-per-store-per-day`로 조정할 수 있다. 표의 값이 기본값이자 정책이며, 눈에 띄게 붐비는 매장을
위한 조정 나사다.

로그인은 IP 키와 계정 키를 함께 쓴다. 한 IP가 여러 계정을 훑는 경우와 여러 IP가 한 계정을
노리는 경우가 서로 다른 공격이라 한쪽만 막으면 다른 쪽이 그대로 열린다.

매장 단위 한도와 고객별 2일 쿨타임은 별개다. 쿨타임은 phone hash 기준 참여 제한이고 그대로
authoritative하며, rate limit은 그 앞단의 자원 보호다. 정상 QR 손님이 매장 한도에 걸리는 일이
없도록 매장 분당 한도는 실제 회전율보다 넉넉히 잡는다.

429 응답:
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "GAME_RATE_LIMITED",
    "message": "잠시 후 다시 시도해 주세요."
  }
}
```

# Error Code

2026-09-22 보안 보완으로 추가된 코드:

| code | HTTP | 언제 |
|---|---|---|
| `RATE_LIMITED` | 429 | 공통 공개 throttle |
| `SIGNUP_RATE_LIMITED` | 429 | 가입 IP·사업자등록번호 한도 초과 |
| `GAME_RATE_LIMITED` | 429 | 게임 생성 매장·IP 한도 초과 |
| `STORE_DAILY_LIMIT` | 429 | 매장 1일 게임 생성 상한 초과 |
| `RECOVERY_TICKET_INVALID` | 400 | 쿠폰 회수 티켓 만료·재사용·타 매장·미존재 |
| `STORE_PENDING_APPROVAL` | 403 | 운영자 승인 대기 중인 매장 |
| `STORE_REJECTED` | 403 | 승인 거부된 매장 |
| `OPERATOR_ONLY` | 403 | 운영자 전용 API를 일반 관리자가 호출 |

운영자 소유권 변경에서 대상 이메일 계정이 없으면 400 `ADMIN_NOT_FOUND`다.

기존 코드:

```text
STORE_NOT_FOUND
STORE_INACTIVE
QR_TOKEN_INVALID
QR_TOKEN_REVOKED
INVALID_PHONE
PRIVACY_CONSENT_REQUIRED
TERMS_CONSENT_REQUIRED
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
