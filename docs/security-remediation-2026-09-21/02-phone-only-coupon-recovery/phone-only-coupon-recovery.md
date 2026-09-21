# 전화번호만으로 활성 쿠폰 bearer token을 회수하는 문제

| 항목 | 내용 |
| --- | --- |
| 기준 revision | `3877b6211008004a48dce07e27724f4304b5c01d` |
| 심각도 | Medium |
| 신뢰도 | High (정적 소스 근거) |
| 분류 | 인증·권한 / 민감한 기능의 bearer capability 노출 |
| CWE | CWE-200, CWE-639 |
| 영향 파일 | `backend/src/main/java/com/yutreview/PublicController.java:13-22`, `backend/src/main/java/com/yutreview/CoreServices.java:238-242,302-304` |
| 검증 범위 | 소스 정적 검토만 수행. 런타임, 배포, 태그·릴리스는 확인하지 않음 |

## 1. 요약

공개 고객 상태 API가 요청 본문의 `name`을 받지만 사용하지 않고, 매장 토큰과 전화번호만으로 참여 상태를 조회한다. 활성 쿠폰이 있으면 응답에 해당 쿠폰의 `couponToken`을 그대로 포함한다. 쿠폰 토큰은 별도 로그인이나 추가 소유 증명 없이 `GET /api/public/coupons/{token}`에서 사용되는 bearer capability이므로, 전화번호를 아는 제3자가 고객 이름을 몰라도 활성 쿠폰을 회수할 수 있다.

## 2. 근본 원인

`PublicController.state`는 `r.name`을 검증·전달하지 않고 `participation.state(s.id, r.phone)`만 호출한다. `ParticipationService.state`는 `storeId + phones.hash(phone)`로 `ISSUED` 쿠폰을 찾고, 찾은 엔티티를 반환한다. 컨트롤러는 그 엔티티의 `couponToken`을 무조건 공개 응답 필드로 직렬화한다. 토큰 생성 자체는 무작위지만, 조회 경로가 전화번호 지식만으로 토큰을 발급하는 구조를 만든다.

## 3. 검증

현재 revision의 다음 소스 흐름을 대조했다.

- `PublicController.java:13-22`: `CustomerStateRequest`에 `name`이 있으나 `state` 메서드에서는 `phone`만 `ParticipationService`로 전달하고, 활성 쿠폰의 `couponToken`을 응답한다.
- `CoreServices.java:238-242`: `ParticipationService.state`가 매장과 전화번호 해시로 활성 쿠폰을 조회하고 쿠폰 객체를 상태에 담는다.
- `CoreServices.java:302-304`: 쿠폰 토큰은 무작위로 생성되지만, 그 토큰을 보유하면 `CouponService.get` 경로에서 쿠폰 내용을 조회할 수 있다.

재현 요청, 런타임 실행, 실제 데이터·로그 검증은 수행하지 않았다. 따라서 외부 공격자가 실제 고객 전화번호를 획득했는지와 운영 환경의 노출 정도는 미확인이다.

## 4. 데이터 흐름

`public QR token + phone (+ 무시되는 name)` → `activeQr(token)` → `PhoneService.hash(phone)` → `findFirstByStoreIdAndPhoneHashAndStatusOrderByIssuedAtDesc(..., ISSUED)` → `State.coupon` → 응답 `couponToken` → 토큰 기반 쿠폰 조회.

전화번호는 저장 조회용 해시로 보호되지만, 이 엔드포인트 자체가 전화번호를 인증 요소처럼 취급한다. 반환된 토큰은 전화번호 해시가 아니라 별도의 쿠폰 접근 자격이므로, 한 번 노출되면 이후 요청에서 전화번호를 다시 제시할 필요가 없다.

## 5. 도달 조건과 영향

공격자는 활성 쿠폰이 있는 매장의 공개 QR 토큰과 해당 고객의 전화번호를 알아야 한다. 이름은 필요하지 않다. 조건을 충족하면 쿠폰의 등급·상품·사용 정책·유효기간·토큰을 확인할 수 있고, 토큰을 제3자에게 전달해 쿠폰 조회 capability를 공유할 수 있다. 직원 PIN 없이는 정상 redeem을 완료할 수 없지만, 쿠폰 비밀성·고객의 쿠폰 소유 통제·후속 UI 흐름은 훼손된다. 매장 간 격리는 `storeId` 조회 조건으로 유지되므로, 이 finding은 전화번호를 아는 동일 매장 범위의 회수 문제다.

## 6. 심각도 및 신뢰도

Medium으로 평가한다. 활성 쿠폰이라는 고객 자산의 bearer token이 공개 상태 조회에서 반환되며 이름 검증도 없어 개인정보·쿠폰 접근 통제가 약화된다. 다만 직원 PIN이 별도로 요구되어 즉시 무단 사용까지 입증된 것은 아니고, 런타임 노출·실제 피해·운영 rate limit은 확인하지 못했다. 소스의 입력-조회-응답 경로는 직접 확인되어 정적 근거에 대한 신뢰도는 High다.

## 7. 개선안

전화번호만으로 토큰을 반환하지 않도록 `customer-state`를 possession-bound 짧은 세션 흐름으로 바꾼다. 예를 들어 서버가 상태 조회 시 일회성·짧은 만료의 recovery challenge/session을 만들고, 이후 쿠폰 상세·토큰 반환은 그 세션과 브라우저 보유 비밀(초기 요청에서 생성된 nonce 또는 동일 세션의 HttpOnly 보호 값)의 결합을 요구한다. challenge는 매장·전화번호 해시에 묶고, 재사용·열거·속도 공격을 제한하며, 쿠폰 원문 토큰 대신 서버 세션으로만 쿠폰을 참조하게 한다. 기존 MVP의 “SMS 인증 없음” 규칙을 유지하므로 SMS를 유일한 해결책으로 요구하지 않는다.

구체적으로는 다음을 권고한다.

- 상태 응답에서 `couponToken`을 제거하고, 짧은 수명의 opaque recovery session ID만 반환한다.
- 쿠폰 조회 API는 session ID와 서버가 검증하는 possession-bound secret을 모두 요구하고, 토큰 직접 조회 경로는 동일한 보호를 적용하거나 내부 전용으로 축소한다.
- 이름은 보조 확인값으로 사용할 수 있으나 단독 인증으로 간주하지 말고, 전화번호만으로 세션을 발급하는 경우에도 시도 횟수·속도·만료·단일 사용을 강제한다.
- 세션에는 `storeId`, phone hash, coupon ID, 발급 시각, 만료·사용 여부를 저장하고 다른 매장 또는 다른 고객 쿠폰에 재사용할 수 없게 한다.
- 회귀 테스트로 (a) name 불일치가 토큰을 반환하지 않음, (b) 전화번호만으로 bearer token이 노출되지 않음, (c) 올바른 possession-bound 세션에서만 활성 쿠폰이 조회됨, (d) 만료·재사용·타 매장 세션이 거부됨을 검증한다.

이 문서는 remediation 제안이며, 해당 수정이 구현·배포되었거나 운영 환경에서 검증되었다고 주장하지 않는다.
