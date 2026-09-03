# MVP DEVELOPMENT PLAN

## Phase 0 - Bootstrap
- [x] Git Repository
- [x] Spring Boot Java 17
- [x] Next.js TypeScript
- [x] PostgreSQL
- [x] Docker Compose
- [x] 공통 Error Response

## Phase 1 - Store / Admin
- [x] AdminUser
- [x] Store
- [x] AdminStoreMembership
- [x] 관리자 로그인
- [x] 복수 매장 조회
- [x] 운영자 매장 생성
- [x] 6자리 PIN 생성
- [x] QR Token 생성

완료조건:
```text
운영자가 매장을 만들면 Store + QR + 직원 PIN 생성
```

## Phase 2 - Prize
- [x] TIER_1 자동 생성
- [x] TIER_2 자동 생성
- [x] TIER_3 자동 생성
- [x] 상품명/설명 수정
- [x] SAME_DAY/NEXT_DAY/ANYTIME 설정

## Phase 3 - Customer State
- [x] QR Landing
- [x] 이름/전화번호 입력
- [x] 개인정보 동의
- [x] Phone Normalize
- [x] HMAC Hash
- [x] Active Coupon 조회
- [x] 2일 쿨타임 계산

완료조건:
```text
HAS_ACTIVE_COUPON / CAN_PLAY / COOLDOWN
정확히 반환
```

## Phase 4 - Staff PIN (쿠폰 사용 전용)
- [x] PIN 검증
- [x] retry limit
- [x] 게임 시작 단계에서 직원 확인 제거

완료조건:
```text
쿠폰 사용 처리는 직원 PIN 없이 불가
```

## Phase 5 - Game Backend
- [x] SecureRandom
- [x] 도 32.5
- [x] 개 32.5
- [x] 걸 12.5
- [x] 윷 12.5
- [x] 모 10
- [x] GamePlay 저장
- [x] animationSeed
- [x] idempotency
- [x] reveal API
- [x] 재추첨 차단

## Phase 6 - Coupon
- [x] Coupon 자동 발급
- [x] Prize Snapshot
- [x] validFrom 계산
- [x] 90일 expiresAt
- [x] Coupon 조회
- [x] PIN 사용 처리
- [x] Atomic redeem

## Phase 7 - 3D Yut
- [ ] GLB 모델
- [x] R3F Canvas
- [x] Rapier RigidBody
- [x] throw impulse
- [x] air rotation
- [x] collision
- [x] bounce
- [x] roll
- [x] settle
- [x] target quaternion
- [x] camera
- [x] sound
- [ ] particle
- [x] 모바일 최적화

완료조건:
```text
서버 결과와 최종 윷 면 100% 일치
```

## Phase 8 - Admin Dashboard
- [x] 오늘 참여자
- [x] Tier별 당첨
- [x] 쿠폰 사용량
- [x] 상품 설정
- [x] QR 관리
- [x] PIN 재발급
- [x] 참여내역
- [x] 쿠폰내역

## Phase 9 - QA
### 참여
- [x] 같은 매장 2일 쿨타임
- [x] 다른 매장은 별도 참여 가능
- [x] 미사용 쿠폰 우선

### Game
- [x] 버튼 중복클릭
- [x] API 재호출
- [x] 새로고침
- [x] reveal 중복

### Coupon
- [x] 당일 사용
- [x] 익일 사용
- [x] 아직 사용불가
- [x] 만료
- [x] 이중 사용
- [x] 다른 매장 PIN 실패

### QR
- [x] 잘못된 token
- [x] revoked QR
- [x] inactive store

## 권장 실제 개발 순서
```text
Store
→ QR/PIN
→ Prize
→ Customer State
→ Game Backend
→ Coupon
→ 고객 기본 UI
→ 3D Yut
→ Admin Dashboard
→ Analytics
→ Deploy
```

## MVP 완료 정의
고객이 실제 매장에서 아래 흐름을 처음부터 끝까지 수행할 수 있어야 한다.

```text
리뷰 작성
→ 직원 확인
→ QR
→ 개인정보 입력
→ 3D 윷놀이
→ 당첨
→ 쿠폰 확인
→ 사용 가능 시 직원 PIN
→ 사용완료
→ 2일 후 재참여
```


---

## Phase 10 - Local Field Test Deployment

- [x] production-mode frontend Docker image
- [x] production-mode backend Docker image
- [x] PostgreSQL persistent volume
- [x] Nginx single-origin reverse proxy
- [x] Host `8088 -> nginx:80`
- [x] `/api` backend proxy
- [x] Docker healthchecks
- [x] `.env.field-test.example`
- [x] optional `cloudflared` Docker Compose profile
- [x] Cloudflare Quick Tunnel URL 확인 절차
- [x] current-origin 기반 QR 생성
- [x] Tunnel restart 시 QR 재생성 안내
- [ ] 외부 LTE/5G smoke test

완료 기준:

```text
docker compose --profile field-test up -d --build
```

실행 후 Cloudflare public HTTPS URL을 확인하여 다른 네트워크의 휴대폰에서 고객 전체 Flow를 수행할 수 있어야 한다.

자세한 배포/운영 절차는 `12_LOCAL_FIELD_TEST.md`를 따른다.

## Phase 12 - 매장별 등급 수와 확률 자율화

기존 3등급 고정 / 확률 시스템 고정을 매장 설정으로 연다.
가중치는 등급이 아니라 윷 결과에 붙는다. 화면에 실제로 떨어지는 것이
도개걸윷모 다섯 가지라, 등급에 확률을 걸면 던져진 모양과 상품이 어긋난다.

- [x] 문서 잠금 해제 (AGENTS / ERD / API / 보안 / README)
- [x] `Tier` enum 제거, `prizes.rank` 정수 전환
- [x] `store_outcomes` 테이블 (결과별 weight + prize_rank)
- [x] `coupons.prize_rank_snapshot`, `game_plays.prize_rank`
- [x] 현장테스트 DB 볼륨 재생성
- [x] 가중치 기반 `GameResultGenerator`
- [x] `GET/PUT /admin/stores/{id}/game-config` 원자 저장
- [x] 설정 서버 검증 4종 (범위 / 합 / 등급 연속 / 상품 존재)
- [x] 관리자 설정 화면 (3·4·5등급 프리셋 + 가중치 + 실시간 % 미리보기)
- [x] 공개 상품·확률 API와 고객 랜딩 노출
- [x] `labels.ts` 정적 등급 맵 제거
- [x] 회귀 테스트 (설정 변경이 기존 쿠폰 불변 / weight 0 미발생 / 4·5등급 발급)

3D는 변경 대상이 아니다. `yut-throw.ts`와 `YutGame.tsx`는 서버가 준
`yutResult` 다섯 값만 보고 그 면이 나오는 시뮬레이션을 찾는다.
가중치도 등급 수도 이 경로에 들어오지 않는다.

완료조건:
```text
같은 서버에서 3등급 매장과 5등급 매장이 서로 다른 확률로 동시에 운영된다
```

---

## 검증 현황 (2026-09-02)

- 체크된 항목은 `./gradlew test`(7개), `npm run lint/test/build`, Nginx `localhost:8088` 경유 E2E 스모크(33개 확인)로 검증했다.
- 미체크 항목은 다음 이유로 남아 있다.
  - `GLB 모델`, `particle`: 현재는 절차적 3D 윷과 기본 연출만 사용한다. 모델 로딩 경계가 분리되어 있어 이후 GLB/파티클로 교체 가능하다.
  - `외부 LTE/5G smoke test`: Cloudflare 임시 URL과 실제 휴대폰이 필요해 로컬에서 확인할 수 없다.
