# FRONTEND IMPLEMENTATION GUIDE

## Stack
```text
Next.js
React
TypeScript
Tailwind CSS
TanStack Query
Zustand
React Three Fiber
@react-three/drei
@react-three/rapier
```

## 구조
```text
src/
├─ app/
│  ├─ s/[storeToken]/
│  └─ admin/
├─ components/
├─ features/
│  ├─ store/
│  ├─ customer/
│  ├─ game/
│  ├─ coupon/
│  └─ admin/
├─ lib/
├─ hooks/
├─ types/
└─ styles/
```

## 고객 화면
### Landing
- 매장명
- 이벤트 안내
- 리뷰 작성 안내
- 상품 3단계 미리보기
- 참여하기 버튼

### Identify
- 이름
- 전화번호
- 개인정보 동의

### Staff Verify
문구 예:
```text
직원 확인이 필요합니다.
리뷰 작성 여부를 확인한 직원이 6자리 PIN을 입력해주세요.
```

### Game
- 화면 중심 3D Canvas
- `윷 던지기` CTA
- 중복 클릭 차단

### Result
- `도/개/걸/윷/모` 결과 강조
- 당첨상품
- 사용 가능 시점
- 만료 안내(기간은 매장 설정)

### Coupon
상태:
```text
사용 가능
아직 사용 불가
사용 완료
기간 만료
```

직원용 사용처리 버튼을 별도로 강조하되 고객 오클릭 방지를 위해 확인 Dialog + PIN 입력을 거친다.

## API 상태 UX
### HAS_ACTIVE_COUPON
```text
사용하지 않은 당첨 상품이 있어요!
먼저 상품을 확인해주세요.
```

### COOLDOWN
```text
이미 최근에 이벤트에 참여하셨습니다.
9월 4일부터 다시 참여하실 수 있어요.
```

### CAN_PLAY
직원 PIN 화면으로 이동.

## 상태관리
Zustand:
```text
store
customerSession
gamePlayId
animationSeed
couponToken
```

전화번호 전체 원문은 localStorage에 저장하지 않는다.

## 모바일 기준
우선 검증 viewport:
```text
360x800
390x844
430x932
```

3D 최적화:
- DPR 상한
- 저사양 모드
- 1K 중심 Texture
- Shadow 품질 조절
- Postprocessing 최소화

## 관리자 UI
메뉴:
```text
대시보드
상품 설정
QR 관리
직원 PIN
참여 내역
쿠폰 내역
통계
```

상품 설정은 3개 카드:
```text
도·개 / 65%
걸·윷 / 25%
모 / 10%
```
확률은 읽기 전용.

## 디자인 방향
```text
모바일 게임 60%
프리미엄 미니멀 40%
```
키워드:
- 실제 목재 질감
- 부드러운 Motion
- 강한 타격감
- 과하지 않은 Particle
- 깔끔한 Typography
- 결과 순간만 강조
