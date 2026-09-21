# SYSTEM ARCHITECTURE

## 전체 구조
```text
Customer Mobile Browser
        |
        v
   Next.js Web
        |
        | HTTPS REST
        v
 Spring Boot API
        |
   +----+------------+
   |                 |
PostgreSQL       S3 + CDN
   |
 Redis (2차: Rate Limit / Cache)
```

## Frontend 책임
- QR Landing
- 매장 이벤트 안내와 선택 네이버 플레이스 링크
- 고객 정보 입력
- 쿠폰 사용 시 직원 PIN UI
- 쿠폰 UI
- 3D 윷놀이
- 결과 UI
- 관리자 대시보드

Frontend는 게임 결과를 생성하지 않는다.

## Backend 책임
- Store/QR 검증
- 전화번호 정규화 및 hash
- 미사용 쿠폰 조회
- 쿨타임 계산
- 직원 PIN 검증
- SecureRandom 결과 생성
- GamePlay 저장
- Coupon 발급/사용
- 대표 권한 확인
- 통계
- 결과별 가중치와 상품 전체 문서의 원자 저장
- 매장 생성 시 A6 QR 안내물 PNG 생성·DB 저장
- 매장명, QR 토큰, 공개 origin, 안내 문구 변경 시 안내물 갱신

## Domain
```text
auth
admin
store
qr
customer
game
prize
coupon
analytics
notification
common
```

## 게임 생성
```text
POST /games
  ↓
ParticipationService
  ↓
미사용 쿠폰 / 쿨타임 검증
  ↓
GameResultGenerator
  ↓
SecureRandom
  ↓
GamePlay INSERT
  ↓
Coupon INSERT
  ↓
animationSeed 반환
```

GamePlay + Coupon 발급은 하나의 DB Transaction으로 처리한다.

게임 입장에는 리뷰 확인이나 직원 PIN 검증이 없다. 리뷰는 선택 외부 동선이며, 직원 PIN은 쿠폰 사용 트랜잭션에서만 검증한다.

## 이벤트 설정 저장

```text
PUT /api/admin/stores/{storeId}/event-configuration
  -> membership 검증
  -> 결과 5개 + 사용 등급 1..N + 상품 전체 검증
  -> StoreOutcome + Prize 단일 Transaction 저장
```

검증이나 저장 중 하나라도 실패하면 확률과 상품을 모두 이전 상태로 되돌린다. 기존 발급 쿠폰은 발급 시점 스냅샷을 유지한다.

## 대표 다매장 구조
```text
AdminUser
  |
  +-- AdminStoreMembership
           |
           +-- Store
```

모든 관리자 API는 현재 계정이 해당 Store에 대한 Membership을 갖는지 Backend에서 검증한다.

## 매장 QR 안내물
```text
Store + posterTagline + active StoreQrCode + current public origin
  -> StorePosterService
  -> A6 300dpi PNG
  -> PostgreSQL store_posters
  -> authenticated download
  -> mobile download / OS share sheet
```

현장 테스트는 S3 없이 PostgreSQL에 저장한다. Quick Tunnel 주소가 바뀌면 관리자 QR 화면에서 현재 origin으로 저장본을 다시 만든다.

공개 매장 조회는 `posterTagline`을 빈 문자열 기본값과 함께 제공한다. 프런트엔드는 이를 이벤트 안내 문구로 사용하고, 네이버 플레이스 URL이 설정된 경우에만 선택 링크를 표시한다.

## 알림 확장
MVP:
```text
NotificationService -> NoopNotificationService
```

향후:
```text
NotificationService -> SolapiNotificationService
```
