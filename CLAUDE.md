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
