# CLAUDE.md

`AGENTS.md`가 이 저장소의 개발 규칙 원본이다. 코딩 전에 `AGENTS.md`를 먼저 읽고,
잠긴 비즈니스 규칙(확률, 쿨타임, 쿠폰 정책, 게임 무결성)은 사용자가 명시적으로 바꾸기 전까지 유지한다.
이 문서는 규칙을 복제하지 않고, **현재 구현 상태와 실제로 동작하는 명령**만 기록한다.

## 현재 스택 (문서의 "추천 스택"과 다른 부분 포함)

- 백엔드: Java 17 + Spring Boot 3.4.4 + Spring Data JPA + Spring Security + java-jwt, Gradle
- DB: PostgreSQL 17 (테스트는 H2 PostgreSQL 모드), 스키마는 `ddl-auto=update` (마이그레이션 도구 없음)
- 프런트: Next.js 15 + React 19 + TypeScript + TanStack Query + Zustand + R3F/drei/rapier
- 인프라: Docker Compose (postgres / backend / frontend / nginx, `field-test` 프로파일에 cloudflared)
- **의도적으로 도입하지 않은 것**: QueryDSL, Flyway/Liquibase, Tailwind(`globals.css` 직접 작성), Redis.
  편의를 이유로 추가하지 말 것.

## 코드 배치

백엔드는 패키지를 잘게 쪼개지 않고 `backend/src/main/java/com/yutreview/` 아래 역할별 파일로 모아 둔 상태다.

- `Domain.java` 엔티티/enum, `Repositories.java` 리포지토리
- `CoreServices.java` PhoneService(정규화·HMAC·AES-256-GCM), StoreAccessService, GameResultGenerator,
  StaffVerificationService, PinAttemptLimiter/LoginAttemptLimiter, ParticipationService, GameService, CouponService
- `PublicController.java` 고객 API, `AdminController.java` 관리자 API, `SecurityConfig.java` JWT 필터,
  `ApiSupport.java` 에러 응답 규격, `Bootstrap.java` 현장테스트용 초기 계정/매장 시드
- 테스트: `backend/src/test/java/com/yutreview/CoreRulesTest.java` (확률 경계, 멱등 발급, 쿨타임/쿠폰,
  1회용 직원 승인, NEXT_DAY/만료/PIN, 개인정보 암복호화)

프런트는 `frontend/src/app/s/[storeToken]/...` 고객 플로우, `frontend/src/app/admin/...` 관리자 화면,
3D 윷은 `frontend/src/components/yut/YutGame.tsx` 한 파일이다.

## 검증 명령 (실제로 존재하는 것만)

```bash
cd backend && ./gradlew test
cd frontend && npm run lint && npm test && npm run build
```

## 로컬 현장 테스트

```powershell
docker compose --env-file .env.field-test up -d --build
docker compose --env-file .env.field-test --profile field-test up -d   # Cloudflare Quick Tunnel 추가
```

- `.env.field-test`는 `.gitignore` 대상이며 커밋하지 않는다. 값이 사라졌으면
  `.env.field-test.example`을 복사해 다시 채운다.
- `PHONE_HMAC_SECRET`/`PHONE_ENCRYPTION_KEY`를 바꾸면 기존 DB의 쿨타임 조회와 이름/번호 복호화가 깨진다.
  기존 볼륨을 유지한 채 값을 바꾸지 말 것.
- 접근 경로는 Nginx 단일 오리진 `http://localhost:8088`뿐이다. postgres/backend/frontend는 호스트 포트를 열지 않는다.

## 알려진 제약

- PIN·로그인 시도 제한은 인메모리 `ConcurrentHashMap` 기반이라 단일 인스턴스 전제다.
- Cloudflare 터널을 경유하면 Nginx가 보는 `$remote_addr`가 cloudflared 컨테이너 IP로 고정되어
  PIN 시도 제한이 매장 단위 전역 제한(분당 10회)처럼 동작한다. 현장 테스트 규모에서는 문제가 없지만,
  실제 운영에서 클라이언트별 제한이 필요하면 신뢰 가능한 프록시 설정과 함께 real_ip 처리를 추가해야 한다.
- 터널 주소가 바뀌면 관리자 QR 화면을 새 주소로 열어 QR을 다시 만들어야 한다(토큰 자체는 유지된다).

## 3D 윷 (건드리기 전에 반드시 읽을 것)

- `.agents/skills/r3f-*`(= `.claude/skills/r3f-*`)에 R3F/Rapier 지침이 설치돼 있다.
  물리·애니메이션·씬 구조를 바꾸기 전에 `r3f-physics`, `r3f-animation`, `r3f-fundamentals`를 먼저 읽는다.
- **화면에서는 물리를 돌리지 않는다.** `yut-throw.ts`가 던지기 전에 윷 4개를 각각 독립 월드에서
  정지할 때까지 시뮬레이션하고, 정지 면이 서버 결과와 일치하는 실행만 채택해 매 스텝을 기록한다.
  화면은 그 기록을 재생만 하며 마지막 프레임에서 멈춘다.
- 그래서 **착지한 면도 위치도 이후에 바뀌지 않는다.** 실시간 물리 + 사후 quaternion 보정 방식으로
  절대 되돌리지 말 것(그 방식이 "던진 뒤 모양이 바뀐다"는 고질적 문제의 원인이었다).
- 회전은 X축(앞뒤 텀블링) 전용. Z축(긴 축) 회전은 부메랑처럼 보이고 면까지 바꾼다.
- 윷 모양은 전통 장작윷(반달 단면, 배=평평·약간 볼록, 등=둥근 면). 콜라이더는 같은 단면의 convex hull.
- 물성 조정 시 반드시 `npm test`로 5개 결과(도개걸윷모)가 모두 재현되는지 확인한다
  (`src/features/game/yut-throw.test.ts`). 반발계수·접촉 감쇠를 키우면 착지 면이 뒤집힌다.
- `@react-three/rapier`는 더 이상 쓰지 않는다(렌더 경로에 물리 없음). 시뮬레이션은 `@dimforge/rapier3d-compat` 직접 사용.

## Nginx CSP 주의

`script-src`에 `'unsafe-inline'`(Next.js 하이드레이션 인라인 스크립트)과 `'wasm-unsafe-eval'`(Rapier WASM)이
반드시 있어야 한다. 빼면 화면이 흰 채로 뜨고 콘솔 에러도 남지 않는다.

## 관리자 계정

직원 PIN은 **쿠폰 사용 처리에만** 쓴다. 게임 시작에는 직원 확인이 없다(2일 쿨타임과 미사용 쿠폰
우선 규칙이 유일한 참여 제한). 상품 등급 표기는 고객 화면 기준 도·개=3등, 걸·윷=2등, 모=1등이다.

매장 대표는 `/admin/signup`에서 직접 가입한다(대표 이름·연락처·아이디·비밀번호·이메일·상호명·사업자등록번호).
가입 한 트랜잭션에서 계정·매장·OWNER 멤버십·QR 토큰·상품 3개가 생성되고 직원 PIN은 응답에서 한 번만 노출된다.
로그인 입력값(`loginId`)에는 아이디와 이메일을 모두 받는다.

