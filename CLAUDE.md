# CLAUDE.md

`AGENTS.md`가 이 저장소의 개발 규칙 원본이다. 코딩 전에 `AGENTS.md`를 먼저 읽고,
잠긴 비즈니스 규칙(쿨타임, 쿠폰 정책, 게임 무결성)은 사용자가 명시적으로 바꾸기 전까지 유지한다.
확률과 등급 수는 2026-09-03부터 매장 설정이다. 서버 검증과 무결성 규칙 자체는 여전히 잠겨 있다.
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
- `CoreServices.java` PhoneService(정규화·HMAC·AES-256-GCM), StoreAccessService, GameConfigService,
  GameResultGenerator, PinAttemptLimiter/LoginAttemptLimiter, ParticipationService, GameService, CouponService
- `PublicController.java` 고객 API, `AdminController.java` 관리자 API, `SecurityConfig.java` JWT 필터,
  `ApiSupport.java` 에러 응답 규격, `Bootstrap.java` 현장테스트용 초기 계정/매장 시드
- 테스트: `backend/src/test/java/com/yutreview/CoreRulesTest.java` (가중치 경계, 설정 검증 4종,
  3·4·5등급 발급, 발급 쿠폰 동결, 멱등 발급, 쿨타임/쿠폰, NEXT_DAY/만료/PIN, 개인정보 암복호화)

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

## 매장별 등급 수와 확률 (2026-09-03)

확률은 **등급이 아니라 윷 결과에 붙는다.** 화면에 실제로 떨어지는 것이 도개걸윷모 다섯 가지라,
등급에 확률을 걸면 던져진 모양과 드리는 상품이 어긋난다. 이 방향을 뒤집지 말 것.

- `store_outcomes` 매장당 5행 = (윷 결과, weight 0~1000, prize_rank). 쓰이는 prize_rank의 종류 수가
  그 매장의 등급 수(1~5)다. 3/4/5등급은 관리자 UI의 프리셋일 뿐 백엔드에 분기가 없다.
- 등급은 `rank` 정수이며 **1이 1등**. 뒤집힌 의미의 `Tier` enum은 제거했다. 등급 수가 매장마다
  다르면 `TIER_1`이 3등인지 5등인지 정할 수 없어서 되돌리면 안 된다.
- `GameConfigService.save()`는 5개 결과 전체를 한 트랜잭션으로만 저장한다. 부분 저장 엔드포인트를
  추가하지 말 것(확률 표가 반쯤 적용된 상태가 생긴다).
- 저장 시 1..N 등급의 상품 행을 만들고 활성화하며, 빠진 등급의 상품은 **비활성화만** 한다.
  발급된 쿠폰이 그 상품을 FK로 참조하므로 삭제하면 안 된다.
- 쿠폰은 발급 시점에 `prize_rank_snapshot`과 상품명·설명·사용정책을 동결한다. 사장이 나중에
  확률이나 상품을 바꿔도 고객이 이미 받은 쿠폰은 그대로다.
- 고객 화면에도 확률을 표시한다. weight 0이라 도달할 수 없는 등급은 공개 목록에서 제외한다
  (`PublicController.publicPrizes`). 받을 수 없는 상품을 확률과 함께 광고하지 않기 위한 규칙이다.
- 3D는 이 변경과 무관하다. `yut-throw.ts`는 서버가 준 `yutResult` 다섯 값만 본다.

## 요금제와 AI (2026-09-04)

3단계 요금제(BASIC/STANDARD/PRO)와 소담 AI 기능을 넣었다. 상세 규칙은 AGENTS.md에 있고
여기에는 구현 위치만 적는다.

- `Subscription.java` PlanEntitlementService(무엇이 열리는지), SubscriptionService(현재 등급)
- `LlmProvider.java` 공급자 경계 + OpenAiLlmProvider + FakeLlmProvider
- `AiContextService.java` **LLM 입력을 만드는 유일한 자리.** 집계와 공개 라벨만 통과한다.
- `AiQuotaService.java` 월 한도(조건부 UPDATE)와 사용 기록, `AiPromptService.java` 프롬프트·스키마
- `AiService.java` 도구 레지스트리 + 네 기능의 공통 경로, `AiController.java` 관리자 전용 엔드포인트
- `AnalyticsService.java` 요금제로 갈리는 상세 분석과 집계 CSV
- `WeeklyReportScheduler.java` PRO 주간 리포트 자동 생성(월요일 새벽)
- 테스트: `SubscriptionAiTest.java` (등급별 허용/거부, 한도 경계와 동시성, PII 배제, 타 매장 차단,
  AI 장애 시 고객 흐름 정상)

되돌리면 안 되는 지점:

- `Entitlement` enum에 게임 관련 항목을 넣지 말 것. 목록에 없다는 것이 "등급으로 팔지 않는다"는 뜻이다.
- 한도 차감을 읽고-쓰기로 바꾸지 말 것. 동시 요청이 한도를 넘긴다.
- 쿼터 행 생성 실패를 같은 트랜잭션에서 잡지 말 것. 영속성 컨텍스트가 오염돼 다음 flush에서 죽는다.
- 요금제·AI 문자열을 공용 `features/labels.ts`에 두지 말 것. 고객 번들로 샌다.
  관리자 전용은 `features/admin/labels.ts`에 둔다.
- 분석 보관기간과 개인정보 보존(120일)을 한 값으로 합치지 말 것.
- 공급자 선택을 `@ConditionalOnProperty`로 되돌리지 말 것. `AI_PROVIDER` 오타 하나로 빈이 하나도
  등록되지 않아 컨텍스트가 뜨지 않고, 관리자 기능 설정 실수가 손님 흐름까지 멈춘다.
- 모델 호출을 `@Transactional` 안에 넣지 말 것. 45초 응답 대기 동안 커넥션을 붙들어 풀이 마른다.
- 되돌릴 수 있는 실패는 공급자에 닿기 전 것뿐이다. 타임아웃과 응답 형식 오류는 이미 과금됐다.
- 관리자 자유 입력(`tone`/`additionalRequest`/채팅)은 `Inputs`가 아니라
  `AiContextService.withoutPersonalData`를 지난다. 여기가 유일한 PII 유입 경로였다.
- 등급 변경은 `SYSTEM_ADMIN`만. 멤버십 검사를 운영자 검사보다 먼저 두지 말 것(운영자는 어느 매장의
  멤버도 아니라서 자기가 해야 할 변경을 스스로 막게 된다).

기본 공급자는 fake다. 실제 호출은 `AI_PROVIDER=openai`와 `OPENAI_API_KEY`가 있을 때만 일어난다.

## 스키마 변경

마이그레이션 도구가 없고 `ddl-auto=update`는 컬럼 타입 변경과 NOT NULL 제거를 못 한다.
2026-09-03 등급/확률 작업에서 postgres 볼륨을 재생성했다(`down -v` 후 `up -d --build`).
`.env.field-test`의 `PHONE_HMAC_SECRET`/`PHONE_ENCRYPTION_KEY`는 유지했으므로 파일은 그대로 쓰면 된다.
운영 데이터가 생긴 뒤에는 이 방법을 쓸 수 없으니 그때는 마이그레이션 절차를 먼저 정해야 한다.

## 관리자 계정

직원 PIN은 **쿠폰 사용 처리에만** 쓴다. 게임 시작에는 직원 확인이 없다(2일 쿨타임과 미사용 쿠폰
우선 규칙이 유일한 참여 제한). 상품 등급 표기는 `rank`(1이 1등)이며 결과별 매핑은 매장 설정이다. 기본값은 도·개=3등, 걸·윷=2등, 모=1등.

매장 대표는 `/admin/signup`에서 직접 가입한다(대표 이름·연락처·아이디·비밀번호·이메일·상호명·사업자등록번호).
가입 한 트랜잭션에서 계정·매장·OWNER 멤버십·QR 토큰·기본 3등급 상품·기본 가중치 설정이 생성되고
직원 PIN은 응답에서 한 번만 노출된다.
관리자 로그인은 **이메일 + 비밀번호**뿐이다. 아이디(`loginId`) 개념은 2026-09-03에 제거했다.
같은 계정으로 `/admin`에서 매장을 더 추가할 수 있고(사업자등록번호는 매장마다 달라야 한다),
한 계정당 상한은 `AdminController.MAX_STORES_PER_ADMIN`이다.

손으로 넣는 값은 백엔드 `Inputs`(CoreServices.java)와 프런트 `features/normalize.ts`가 같은 규칙을
쓴다. 휴대전화는 숫자만 남겨 `010` + 8자리, 사업자등록번호는 숫자 10자리. 화면은 입력 자체를
막고 서버가 다시 검증한다. 정규식을 화면마다 새로 적지 말 것.

`admin_users.login_id` 컬럼은 `ddl-auto=update`가 못 지워서 nullable인 채로 DB에 남아 있다.
쓰는 코드는 없다.

