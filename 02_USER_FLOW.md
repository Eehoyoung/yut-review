# USER FLOW

## 고객 Flow
```mermaid
flowchart TD
    A[매장 QR 스캔] --> B[매장 이벤트 페이지]
    B --> C[이름/전화번호 입력]
    C --> D{미사용 쿠폰 존재?}
    D -- Yes --> E[기존 쿠폰 사용 페이지]
    E --> F[직원용 사용 버튼]
    F --> G[직원 PIN 입력]
    G --> H[사용 완료]
    D -- No --> I{2일 쿨타임 종료?}
    I -- No --> J[재참여 가능일 안내]
    I -- Yes --> M[게임 생성]
    M --> N[3D 윷 애니메이션]
    N --> O[당첨 결과 공개]
    O --> P[쿠폰 발급]
```

## 고객 Route
### `/s/{storeToken}`
- 매장명
- 이벤트 안내
- 네이버 리뷰 안내
- 참여 버튼

### `/s/{storeToken}/identify`
- 이름
- 전화번호
- 개인정보 동의

### `/s/{storeToken}/coupon/{couponToken}`
- 상품명
- 상태
- 사용 가능일
- 만료일
- 직원용 사용 버튼

### `/s/{storeToken}/game`
- 3D 윷놀이

### `/s/{storeToken}/result/{playId}`
- 윷 결과
- 당첨상품
- 사용 가능기간

### `/admin/stores/{storeId}/qr`
- 서버에 저장된 매장명 포함 A6 QR 안내물 미리보기
- PNG 휴대폰 저장
- OS 공유 시트로 카카오톡·메일 등 공유
- 현재 공개 origin으로 안내물 재생성

## 관리자 Flow
```mermaid
flowchart TD
    S[매장 회원가입] --> A[로그인]
    S --> T[A6 QR 안내물 서버 자동 생성]
    A --> B[매장 목록]
    B --> C[매장 선택]
    C --> D[대시보드]
    D --> E[상품 관리]
    D --> F[QR 안내물 저장/공유]
    D --> G[직원 PIN]
    D --> H[참여 내역]
    D --> I[쿠폰 내역]
    D --> J[통계]
```

## 고객 식별 후 판정 우선순위
1. Store 활성 상태
2. QR 유효성
3. 미사용 쿠폰
4. 최근 참여일
5. 2일 쿨타임
6. 직원 PIN
7. 게임 생성
