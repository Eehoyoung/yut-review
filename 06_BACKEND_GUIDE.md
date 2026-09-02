# BACKEND IMPLEMENTATION GUIDE

## Stack
```text
Java 17
Spring Boot 3.x
Spring Security
Spring Data JPA
QueryDSL
Gradle
PostgreSQL 17
```

## 패키지
```text
com.yutreview
├─ common
│  ├─ config
│  ├─ error
│  ├─ response
│  ├─ security
│  └─ util
├─ auth
├─ admin
├─ store
├─ qr
├─ customer
├─ game
├─ prize
├─ coupon
├─ analytics
└─ notification
```

Domain 내부:
```text
game/
├─ controller
├─ service
├─ domain
├─ repository
├─ dto
└─ policy
```

## 게임 결과
`java.security.SecureRandom` 사용.

```java
double r = secureRandom.nextDouble();
if (r < 0.325) return DO;
if (r < 0.650) return GAE;
if (r < 0.775) return GEOL;
if (r < 0.900) return YUT;
return MO;
```

## 참여 정책
`ParticipationPolicyService`

책임:
```text
findActiveCoupon()
calculateNextPlayableDate()
canPlay()
```

판정 순서:
```text
Store/QR 활성
→ Active Coupon
→ Last playedDate
→ 쿨타임
→ Staff verification
```

## 쿨타임
```java
LocalDate nextPlayableDate = lastPlayedDate.plusDays(2);
boolean canPlay = !today.isBefore(nextPlayableDate);
```

`Clock`을 Bean으로 주입해 테스트 가능하게 한다.
기준 Zone: `Asia/Seoul`.

## 직원 PIN
생성:
```text
SecureRandom 100000~999999
```
Hash:
```text
BCrypt 또는 Argon2
```
비교:
```java
passwordEncoder.matches(inputPin, store.getStaffPinHash())
```

## 게임 멱등성
게임 생성 Request는 `idempotencyKey`를 가진다.
DB UNIQUE 제약으로 중복 생성 방지.

## Game + Coupon 트랜잭션
```text
BEGIN
GamePlay INSERT
Coupon INSERT
COMMIT
```
실패 시 모두 rollback.

## 상품 사용일 계산
```text
SAME_DAY -> now
ANYTIME  -> now
NEXT_DAY -> 다음날 00:00
```

만료:
```text
발급 LocalDate + 90일의 23:59:59
```

## 쿠폰 이중 사용 방지
Pessimistic Lock 또는 Atomic Update 사용.

```sql
UPDATE coupons
SET status='REDEEMED', redeemed_at=NOW()
WHERE id=? AND status='ISSUED';
```
영향 행이 1개일 때만 성공.

## 전화번호
Normalize:
- 공백/하이픈 제거
- 숫자만 허용
- 010 시작
- 11자리

저장:
```text
phone_hash: HMAC-SHA256
phone_encrypted: AES-GCM
phone_last4: 마지막 4자리
```

Key는 환경변수/Secret Manager로 관리.

## Notification 추상화
```java
public interface NotificationService {
    void sendCouponIssued(...);
}
```
MVP: `NoopNotificationService`
향후: `SolapiNotificationService`

## 필수 테스트
- 확률 구간/Tier mapping
- 쿨타임 계산
- 미사용 쿠폰 우선
- 게임 중복 생성
- Reveal 재요청
- 다른 매장 PIN 실패
- 쿠폰 이중 사용
- NEXT_DAY 당일 사용 차단
- 만료 쿠폰 차단
