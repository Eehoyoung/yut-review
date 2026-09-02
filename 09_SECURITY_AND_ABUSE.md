# SECURITY / ABUSE / PRIVACY

## QR 공유
위협:
- 고객이 매장 QR 사진을 외부에 공유

대응:
- QR만으로 게임 생성 불가
- 직원 PIN verification이 필수

## 직원 PIN 유출
MVP 대응:
- 매장별 고유 6자리
- 관리자에서 재발급
- 오입력 Rate Limit
- 서버 로그에 PIN 기록 금지

향후:
- 직원별 계정
- 승인 버튼
- 1회성 직원 승인 code

## PIN 저장
평문 DB 저장 금지 권장.
```text
BCrypt 또는 Argon2 Hash
```

## PIN Rate Limit
권장 시작값:
```text
IP + storeToken 기준 1분 10회
```
5회 이상 연속 실패 시 짧은 cooldown 추가 가능.

## 전화번호
전화번호를 중복 참여 판단에 사용하므로 보호 저장.

```text
phone_hash      HMAC-SHA256
phone_encrypted AES-GCM
phone_last4     마지막 4자리
```

로그에 전체 전화번호를 남기지 않는다.

DB에는 이름과 정규화 전화번호를 AES-256-GCM으로 암호화하며 레코드마다 새 96-bit nonce를 사용한다. 중복 참여 조회는 별도 HMAC-SHA256 값으로 수행하고 관리자 화면에는 전화번호 끝 4자리만 제공한다. 암호화 키와 HMAC 키는 서로 분리된 필수 환경변수이며 저장소에 커밋하지 않는다.

## 관리자 인증

- 관리자 ID는 정규화한 이메일로 유일하게 관리한다.
- 비밀번호는 BCrypt hash만 저장하고 bootstrap 비밀번호는 12자 이상을 요구한다.
- 로그인 실패는 동일 ingress 기준 분당 5회로 제한한다.
- JWT secret은 최소 32자이며 환경변수 누락 시 시작을 실패시킨다.
- 관리자 매장 API는 JWT claim만 믿지 않고 DB의 현재 역할과 매장 membership을 매 요청 재검증한다.
- 관리자 JWT는 브라우저 sessionStorage에만 두며 로그아웃/브라우저 종료 시 폐기한다.

## 게임 조작 방지
금지:
```text
Frontend Math.random()으로 결과 생성
```

필수:
- 서버 SecureRandom
- 결과 DB 선저장
- Idempotency
- 직원 verification token

## 쿠폰 조작 방지
쿠폰 URL은 순차 ID가 아닌 긴 난수 Token 사용.

사용 처리:
- 상태 `ISSUED`인지 확인
- validFrom/expiresAt 확인
- 해당 매장 PIN 확인
- Atomic update 또는 DB Lock

## 매장 간 권한
모든 Admin Store API는 Backend에서 Membership 확인.

```text
로그인 계정이 Store A 소유자라도
Store B API는 403
```

## 개인정보 동의
게임 전 개인정보 수집 동의 필수.

수집 항목:
- 이름
- 전화번호
- 참여 매장
- 게임 결과
- 당첨상품/쿠폰 상태
- 참여/사용 시각

실서비스 오픈 전 개인정보 처리방침/보유기간을 별도 확정한다.

## 리뷰 운영
MVP는 특정 별점이나 긍정적인 내용 작성을 시스템적으로 요구하지 않는다.
직원은 리뷰 `작성 여부`만 확인하는 구조로 둔다.
출시 직전 네이버 및 관련 리뷰/광고 정책 최신본을 별도 점검한다.
