# CLAUDE.md

`AGENTS.md`가 이 저장소의 개발 규칙 원본이다. 코딩 전에 `AGENTS.md`를 먼저 읽고,
잠긴 비즈니스 규칙(쿨타임, 쿠폰 정책, 게임 무결성)은 사용자가 명시적으로 바꾸기 전까지 유지한다.
확률과 등급 수는 2026-09-03부터 매장 설정이다. 서버 검증과 무결성 규칙 자체는 여전히 잠겨 있다.
이 문서는 규칙을 복제하지 않고, **현재 구현 상태와 실제로 동작하는 명령**만 기록한다.

## 현재 스택 (문서의 "추천 스택"과 다른 부분 포함)

운영 기준선은 `10_DEPLOYMENT.md`의 날짜가 있는 절을 먼저 본다. 로컬 작업 트리의 기능·설정은 배포
증거가 아니다. 특히 2026-09-26의 운영자 OTP, 관리 포트, SEO 변경은 공개 DOM/응답과 이미지 태그를
확인하기 전까지 "배포됨"이라고 쓰지 않는다.

- 백엔드: Java 17 + Spring Boot 3.4.4 + Spring Data JPA + Spring Security + java-jwt, Gradle
- DB: PostgreSQL 17 (테스트는 H2 PostgreSQL 모드), 스키마는 `ddl-auto=update` (마이그레이션 도구 없음)
- 프런트: Next.js 15 + React 19 + TypeScript + TanStack Query + Zustand + R3F(+ `@dimforge/rapier3d-compat` 직접 사용)
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

## 프로덕션 도메인 및 리브랜딩 전환

- 2026-09-23 사용자가 서비스명을 `소담한판`, canonical origin을
  `https://hanpan.sodamlabs.kr`로 확정했다. 실행 정본은 `docs/REBRAND_AND_DOMAIN_MIGRATION_PLAN.md`다.
- 기존 `https://yut.sodamlabs.kr`은 새 주소의 실제 전환 검증이 끝날 때까지 롤백·리디렉션 대상으로 유지한다.

- 서비스: Sodam Hanpan, canonical origin: `https://hanpan.sodamlabs.kr`
- 운영사: 소담랩스 / 대표자: 이호영 / 사업자등록번호: `358-23-02207`
- 운영은 `SPRING_PROFILES_ACTIVE=prod`, `APP_PUBLIC_ORIGIN=https://hanpan.sodamlabs.kr`를 사용한다.
- production에서는 QR 안내물 origin을 요청 Host가 아니라 위 canonical origin으로 고정한다.
- 브라우저 API와 로그인 이동은 동일 origin 상대경로를 유지하며 CORS를 새로 열지 않는다.
- 인증은 cookie가 아닌 `sessionStorage` Bearer JWT다. production Spring session cookie만 방어적으로
  `Secure`, `SameSite=Strict`이고, Nginx는 HTTP→HTTPS, HSTS와 기존 CSP를 적용한다.
- 개발/field-test의 localhost 및 Quick Tunnel 동작은 유지한다. 운영 Nginx 예시는 `nginx/production.conf`,
  환경 변수 예시는 `.env.production.example`이다.
- 새 Cloudflare 프록시 호스트와 TLS를 먼저 검증한 뒤 기존 호스트를 경로·쿼리 보존 리디렉션한다.
  새 주소의 health, 보안 헤더, QR 절대 URL, 로그인, 고객 게임·쿠폰 E2E가 실패하면 기존 값으로 롤백한다.

## 광고성 문자 수신동의 (2026-09-21)

- 소담랩스 서비스 3개는 `YUT_REVIEW`(소담한판), `REVIEW_PILOT`(리뷰파일럿), `SODAM`(소담)이다.
- 리브랜딩 시 `YUT_REVIEW`는 과거 동의 증적을 잇는 내부 식별자로 유지한다. 최종 브랜드 확정 후 표시 라벨과
  동의문 버전을 바꾸되 기존 `marketing_consent_events`를 수정하거나 다른 서비스 동의로 합치지 않는다.
- 관리자 가입에서 서비스별 선택 체크박스를 기본 해제로 표시한다. 전부 거부해도 가입과 기능에 영향이 없다.
- `marketing_consent_events`는 동의·거부·철회를 append-only로 남긴다. 발송 가능 여부는 서비스별 최신
  이벤트만 보며 다른 서비스 동의를 재사용하지 않는다.
- 로그인 후 `/admin/marketing-consents`에서 즉시 변경·철회한다. 동의문 정본은 `/legal/marketing`이다.
- 실제 SMS 발송 연동은 아직 없다. 향후 발송기는 최신 동의 확인, `(광고)`, 소담랩스, 무료 수신거부,
  야간 전송 제한을 서버에서 강제해야 한다.

## 알려진 제약

- PIN·로그인·가입·게임 시도 제한은 DB 카운터(`rate_counters`)라 인스턴스가 늘어도 같이 센다.
  Redis는 여전히 도입하지 않는다.
- 운영 Nginx는 Cloudflare 대역에서만 `CF-Connecting-IP`를 real_ip로 해석하고, 백엔드는
  `TRUSTED_PROXY_CIDRS` 안의 peer가 보낸 `X-Real-IP`만 믿는다. 개발용 Quick Tunnel 경로는
  여전히 cloudflared 컨테이너 IP로 뭉치므로, 현장 테스트에서는 IP 기준 제한이 매장 전역처럼 동작한다.
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

## 쿠폰 사용 기한 (2026-09-04)

- `store_event_settings` 매장당 1행 = `couponValidityDays`(기본 90, 범위 1~365). 행이 없으면 90으로 동작하므로
  기존 매장 백필이 필요 없다. `StoreSubscription`과 같은 전략이라 컬럼이 nullable로 남지 않는다.
- 만료 계산은 `StoreEventSettingsService.expiresAt` 한 곳뿐이다. 기준은 발급일이 아니라 **validFrom의 KST 날짜**이며,
  `+ (days - 1)일의 23:59:59`다. NEXT_DAY 상품이 하루를 손해 보지 않게 하려는 것이고, 그 덕에
  `expiresAt > validFrom`이 항상 성립한다.
- **2026-09-04에 정의가 바뀌었다.** 이전 구현은 `발급일 + 90일`이라 실제로는 91 달력일이었다. 지금은 90 달력일이다.
  ANYTIME/SAME_DAY 쿠폰이 하루 짧아졌고 NEXT_DAY는 동일하다. 이미 발급된 쿠폰은 건드리지 않았다.
- 설정은 **그 이후 발급되는 쿠폰에만** 적용된다. 발급 시점에 계산해 `expiresAt`에 동결하며, 상품명·등급 스냅샷과
  같은 이유다. 이미 발급된 쿠폰을 다시 계산하는 코드를 추가하지 말 것.
- 요금제로 차등하지 않는다. 모든 등급에서 같이 쓴다(`Entitlement`에 넣지 말 것).

## 화면 공통 규칙 (2026-09-04 감사 반영)

- 모달은 `features/ui/Dialog.tsx` 하나뿐이다. 네이티브 `<dialog>` + `showModal()`이라 포커스 트랩·Esc·
  배경 inert를 브라우저가 처리한다. div 모달이나 `window.confirm`으로 되돌리지 말 것.
- `--line`(장식 구분선)과 `--line-control`(컨트롤 경계, 3:1)은 다른 토큰이다. 합치지 말 것.
- 3D 던지기는 `prefers-reduced-motion`에서 정지 자세를 즉시 보여준다. 시뮬레이션 자체는 그대로 돈다.
- 연출(3D) 실패가 결과 도달을 막지 않는다. `reveal()` 성공 후 시뮬레이션이 실패하면 결과 화면으로 보낸다.
  seed가 결정론적이라 재시도해도 같은 실패가 반복되기 때문이다.
- 손님 인트로(`/s/[storeToken]`)는 서버 컴포넌트가 매장 요약을 미리 받아 `initialData`로 넘긴다.
  주소는 `INTERNAL_API_BASE`(compose에서 `http://backend:8080`)이며, 값이 없으면 예전처럼 클라이언트가 가져온다.

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

기본 공급자 모드는 auto다. `OPENAI_API_KEY`가 있으면 OpenAI, 없으면 fake를 선택한다.
CI와 로컬 테스트에서 네트워크 호출을 확실히 막을 때는 `AI_PROVIDER=fake`를 명시한다.

## 보안점검 반영 (2026-09-22)

`docs/security/report.md`의 10건을 코드·테스트·배포 설정에 반영했다. 구현 위치만 적는다.
규칙 원문은 `09_SECURITY_AND_ABUSE.md`, 계약은 `05_API_SPEC.md`에 있다.

- `RateLimits.java` — `RateCounter`(bucket당 1행, 조건부 UPDATE) + `RateLimitService` + `ClientIpResolver`.
  **모든 rate limit이 여기 하나로 모인다.** 인메모리 맵으로 되돌리지 말 것(만료도 상한도 없어서
  서로 다른 키가 들어올수록 메모리가 단조 증가했다). Redis도 도입하지 말 것.
- `Recovery.java` — 5분·1회용 쿠폰 회수 티켓. 평문은 저장하지 않고 SHA-256만 남긴다.
  `customer-state` 응답에서 `couponToken`을 다시 돌려주지 말 것. 그게 원래 문제였다.
- `StoreApproval.java` — `PENDING_APPROVAL` → 운영자 승인. 감사 로그 + `/api/admin/operator/**`.
  거부 사유는 `stores` 컬럼이 아니라 마지막 `REJECT` 이벤트에서 읽는다(사실을 두 곳에 두지 않는다).
- `PhoneHashMigration.java` — HMAC 키 회전의 마지막 단계. 원문이 AES-GCM으로 남아 있어 되돌릴 수 있다.
- `AiChat.java` — 서버가 소유하는 AI 대화 이력. **클라이언트 `history`를 다시 받지 말 것.**
  그 칸이 개인정보·토큰의 유일한 우회로였다.
- `AiContextService.reasonToBlock` — 개인정보·비밀값 차단의 유일한 자리. 전화번호, 이메일, 긴 hex,
  `cp_`/`rt_`/`seed_`, `sk-`, JWT, Bearer, PIN 표기. 여기 말고 다른 곳에 패턴을 또 적지 말 것.
- `Monitoring.java` + `features/admin/ResourceMonitor.tsx` — 운영자 자원 현황.
  차단 수는 `rate_counters`의 `rejected:{code}` bucket에 24시간 창으로 쌓인다. 별도 테이블을 만들지 말 것.
  거절 기록은 **거절을 만든 트랜잭션 밖에서** 커밋한다. 안에서 쓰면 거절만 정확히 전부 사라진다.
- 배포: 이미지는 `.github/workflows/publish-images.yml`이 GHCR에 올리고 Lightsail은 `pull`만 한다.
  **서버에서 `--build`를 붙이지 말 것.** 2GB VM에서 Gradle 빌드가 운영 컨테이너와 메모리를 다툰다.
  `docker-compose.prod.yml`이 `build: !reset null`로 지워 둬서 붙여도 소스 빌드로 새지는 않는다.
  워크플로의 `platforms: linux/amd64`와 Lightsail 인스턴스 아키텍처는 항상 같이 움직여야 한다.
- nginx 설정은 파일 단위 bind mount다. 서버에서 `git pull`로 `nginx/production.conf`가 바뀌면 컨테이너는 옛 inode를 계속 본다.
  `nginx -s reload`로는 반영되지 않으므로 `up -d --no-deps --force-recreate nginx`로 재생성한다(2026-09-26 SEO 배포에서 확인).
- 운영 스크립트: `scripts/generate-production-secrets.sh`(서버에서 키 생성, 화면에 찍지 않음),
  `scripts/verify-production.sh`(배포 후 TLS/DNS/헤더/fail-closed 점검), `scripts/load-test/`(k6).
  계획과 임계값은 `docs/LOAD_TEST_PLAN.md`.
- 테스트: `SecurityRemediationTest.java` (회수 티켓 만료·재사용·타 매장, 게임/가입 429 경계와 TTL purge,
  신뢰 프록시 IP, 승인/거부/재심사/소유권 + 감사 로그, AI 이력 PII 배제, CSV 수식 전체, HMAC 강도·회전,
  차단 집계와 자원 현황).

되돌리면 안 되는 지점:

- 게임 생성에서 매장 행을 `FOR UPDATE`로 잡지 말 것. 한 손님의 게임이 그 매장의 다른 모든 손님을
  줄 세운다. 잠금 단위는 `(매장, phoneHash)`이며 `RateLimitService.lock`이 그 행을 잡는다.
- `RateLimitService`의 행 생성을 호출부 트랜잭션 안에서 하지 말 것. 유니크 충돌이 영속성 컨텍스트를
  오염시켜 다음 flush에서 죽는다(AI 쿼터 행에서 이미 당했다).
- rate limit 카운트를 바깥 트랜잭션에 묶지 말 것. 실패한 요청이 세어지지 않으면 실패를 반복해
  한도를 우회할 수 있다.
- 매장 quota와 고객 쿨타임을 하나로 합치지 말 것. 참여 제한은 여전히 2일 쿨타임이 authoritative다.
- 승인 전 매장에서 포스터를 만들지 말 것. 익명 요청 하나로 큰 PNG를 계속 쌓는 길이 다시 열린다.
- 백엔드는 `app.trusted-proxies` 안의 peer가 보낸 `X-Real-IP`만 믿는다. 무조건 믿던 예전 코드로
  되돌리지 말 것. Nginx와 Spring의 client-IP 정책은 항상 같이 움직여야 한다.
- `RateLimitService.LOCK_TTL`을 늘리지 말 것. 잠금 행은 (매장, 전화번호)마다 생겨서, 수명이 길면
  `rate_counters`가 고객 수와 1:1로 자라고 `MAX_ROWS`에 닿는 순간 정상 손님 전원이 429를 받는다.
  처음에 30일로 뒀다가 부하 테스트에서 게임 8,367건에 잠금 행 8,367개가 쌓이는 것을 보고 1시간으로 줄였다.
- `StorePoster.contentBase64`에 `@Lob`을 붙이지 말 것. PostgreSQL에서 large object로 매핑돼
  컬럼에는 OID만 들어가고, 안내물을 다시 만들 때마다 회수되지 않는 수백 KB가 샌다(실측 3회에 +414KB).
  H2 PostgreSQL 모드는 이 차이를 재현하지 못한다.
- 부하 테스트 1차 결과와 거기서 나온 버그 4건은 `docs/load-test/2026-09-22-first-run.md`에 있다.

### `PHONE_HMAC_SECRET` 형식이 바뀌었다

Base64로 디코딩해 32바이트 이상이어야 하고, 아니면 **기동하지 않는다**. 기존 `.env.field-test`의
원문 문자열 키는 이제 거부된다. 기존 볼륨을 유지한 채 바꾸려면:

1. `PHONE_HMAC_PREVIOUS_SECRET`에 **기존 값 그대로**, `PHONE_HMAC_SECRET`에 새 Base64 키를 넣는다.
2. 재기동한다. 이 동안 쿨타임·쿠폰 조회는 두 해시를 함께 본다.
3. 운영자 계정으로 `POST /api/admin/operator/phone-hash/rehash`를 1회 실행한다.
4. `PHONE_HMAC_PREVIOUS_SECRET`을 비우고 재기동한다.

DB를 버려도 되는 로컬이라면 `docker compose down -v` 후 새 키로 올리는 쪽이 빠르다.

## 초대코드 (2026-09-23)

`Invites.java`. 계정마다 코드 하나, 영문+숫자 6자리. 가입 화면의 선택 입력이고, 자기 코드는
`/admin`(내 매장)에서 본다. **리워드 정책은 아직 없다** — 지금 하는 일은 "누가 누구를 데려왔는가"를
기록하는 것뿐이다.

- **계정 단위다.** 한 사장이 매장을 세 곳 해도 코드는 하나. "내 코드를 알려 준다"가 사람 단위의
  행동이라 그렇다. 매장별로 나누면 사장이 어느 코드를 줘야 하는지 매번 생각해야 한다.
- 알파벳에서 `0/O`, `1/I/L`을 뺐다(31자). 전화로 불러 주거나 문자로 받아 손으로 옮기는 값이라,
  가장 흔한 실패가 그 글자쌍에서 난다. 이 알파벳을 늘리지 말 것.
- **틀린 코드는 가입을 막는다.** 조용히 무시하면 오타를 친 사람이 "추천이 반영됐겠지" 하고 넘어가고,
  그 사실은 리워드를 받을 때가 되어서야 드러난다. 화면도 제출 전에 `존재하지 않는 초대코드예요`를
  보여 주고 다음으로 못 넘어가게 막는다.
- 대소문자·공백·하이픈을 가리지 않는다. 맞는 코드를 들고도 못 쓰게 만들지 않는다.
- **자기 추천 차단은 연락처로 본다**(`INVITE_CODE_SELF`). 같은 사람이 계정을 하나 더 만들어
  자기 코드를 쓰는 길이다. 리워드가 없는 지금은 손해가 없지만, 정책이 붙는 순간 그 전에 쌓인
  기록이 이미 오염돼 있다. 매장을 여러 개 하는 사장은 한 계정에 매장을 추가하므로 정상 사용자를 막지 않는다.
- 코드는 가입 시점에 만들고, 이 기능 이전 계정은 `/admin/me`가 처음 불릴 때 채운다. 백필 스크립트를
  따로 두지 않는 이유는 자기 코드를 처음 보는 순간이 곧 코드가 필요해지는 순간이라서다.
- `GET /api/admin/auth/invite-code/{code}`는 로그인 전에 열려 있다. 가입 화면이 제출 전에 확인해야
  해서다. **IP 분당 30회 한도가 걸려 있다** — 없으면 코드를 전수로 훑어 유효한 값을 모을 수 있다.
- 초대코드 검사는 **가입 한도(사업자번호 24시간 3회)보다 뒤에** 있다. 앞으로 옮기면 한도를 쓰지 않고
  코드를 훑을 수 있게 된다.
- 저장: `admin_users.invite_code`(unique), `invited_by_admin_user_id`(FK),
  `invited_by_code`(사건 시점 값 동결 — 초대한 계정이 사라져도 어느 코드로 들어왔는지는 남아야 한다).
- 테스트: `InviteCodeTest.java` 6건.

## 사업자등록 진위확인 (2026-09-23)

`BusinessRegistry.java`. 공공데이터포털 국세청 API
(`api.odcloud.kr/api/nts-businessman/v1/validate`)로 **사업자등록번호 + 개업일자 + 대표자성명
세 개를 함께** 확인한다. 번호 하나만 보면 인터넷에서 주운 번호로 가입할 수 있다.

운영자 승인 큐를 끈 자리를 이것이 메운다. 사람이 사업자등록증을 보는 단계가 없어졌으므로
둘 중 하나는 반드시 있어야 하고, 지금은 이쪽이다.

- `BUSINESS_VERIFICATION_ENABLED` + `NTS_SERVICE_KEY`. 키 없이 켜면 **기동하지 않는다** —
  조용히 통과하는 것이 최악이라 배포 시점에 드러나게 한다.
- 키는 공공데이터포털의 **일반 인증키(Decoding)** 원문이다. 코드가 한 번만 인코딩하므로
  인코딩된 키를 넣으면 이중 인코딩돼 401이 난다.
- 가입과 **매장 추가 양쪽**에서 검사한다. 한쪽만 하면 계정 하나 만든 뒤 아무 번호나 넣을 수 있다.
- **호출을 `@Transactional` 안에 넣지 말 것.** `AdminSignupService.signUp`은 그래서 메서드
  전체 트랜잭션을 버리고 `TransactionTemplate`으로 쓰기 구간만 감싼다. `AdminController.createStore`는
  `@Transactional`을 떼고 `provision`의 자기 트랜잭션에 맡긴다. 되돌리면 국세청 응답을 기다리는
  동안 DB 커넥션을 붙들어 풀이 마른다.
- **실패를 열어 주지 말 것**(fail closed). 국세청이 죽었을 때 통과시키면 그 시간 동안 아무 번호나
  들어온다. 가입은 손님 흐름이 아니라 급하지 않다. `BUSINESS_VERIFICATION_UNAVAILABLE`은 503이다.
- 진위확인만 보면 **폐업자도 "일치"**다. 등록이 실제로 있었기 때문이다. `b_stt_cd`를 따로 봐서
  폐업(03)·휴업(02)을 막는다. 이 검사를 빼지 말 것.
- 응답 본문을 로그에 남기지 말 것. 오류 본문에 요청 파라미터가 되돌아오고 거기에 대표자 성명이 있다.
- 검증 순서는 곧 비용 순서다. 형식 → 한도 → 중복 → 국세청 → BCrypt·행 생성. 중복 검사를 국세청보다
  먼저 두는 것은 이미 등록된 번호로 외부 호출을 태우지 않기 위해서다(할당량이 있는 유일한 자원이다).
- 확인에 쓴 대표자명·개업일자는 **매장에** 붙는다(`stores.representative_name`, `opening_date`,
  `business_verified_at`). 계정이 아니라 매장인 이유는 소유권이 넘어가도 이 매장이 어느 사업자로
  확인됐는지가 남아야 해서다. `business_verified_at`이 비어 있으면 확인하지 않고 만든 매장이다.
- 화면은 설정을 복제하지 않는다. `GET /api/admin/auth/signup-requirements`가 개업일자 칸을
  띄울지 알려 준다. 가입 폼에 "검증이 켜졌는지"를 또 적지 말 것.
- 테스트: `BusinessRegistryTest.java`. 진짜 국세청을 때리지 않고 같은 규격으로 답하는 작은 서버를
  띄운다. 할당량을 태우지 않고, 네트워크가 끊겨도 우리 코드와 무관하게 빨개지지 않으며, 무엇보다
  실제 사업자등록번호를 fixture에 넣지 않는다.

## 매장 승인제 중단 (2026-09-22)

`STORE_APPROVAL_REQUIRED` 기본값이 **false**다. 셀프 가입이 바로 `ACTIVE`로 열린다.

- **기능을 지운 것이 아니라 꺼 둔 것이다.** 심사 화면·승인 API·감사 로그 전부 그대로 있고
  `STORE_APPROVAL_REQUIRED=true` 한 줄로 되돌아온다. `StoreApproval.java`를 지우지 말 것.
  `SecurityRemediationTest`가 플래그를 켜고 돌아서 되돌릴 때 동작하는지 계속 보장한다.
- 승인을 끄면 사람이 사업자등록증을 보는 단계가 없다. 그 자리는 국세청 진위확인이 메운다(위 절).
  진위확인까지 꺼 두면 **남는 방어선은 중복 금지 하나뿐이다** — 한 번호로 매장을 두 개 만들 수 없다. 가입과 매장 추가 양쪽에서 막고,
  `OperatorAccountTest.aBusinessNumberCannotBeRegisteredTwiceEvenWithApprovalTurnedOff`가 잠근다.
  하이픈 표기 우회도 같이 본다(정규화 뒤에 비교한다).
- **`provision()`이 안내물을 만들지 않는다.** 예전에는 "승인된 매장만" 그려서 익명 가입이 큰 PNG를
  쌓는 것을 막았는데, 승인을 끄면 그 방어가 통째로 사라진다. 안내물은 파생 데이터라 인증된
  다운로드(`AdminController.poster`)가 없으면 그때 만든다. `provision`에 포스터 생성을 되돌리지 말 것.
- 화면은 설정을 복제하지 않는다. 가입 결과는 서버가 준 `approvalRequired`를 그대로 따른다.

## QR 안내물 3종 (2026-09-23)

`StorePosterService.render(PosterVariant, ...)` 하나가 기본(GAME)·이벤트(EVENT)·재방문(REVISIT)을 그린다.
뼈대(상호 → 제목 → QR 판 → 3단계 → 하단 띠)와 QR 판 위치·크기는 셋이 같고 색·장식·문구만 다르다.

- **저장하는 것은 GAME뿐이다.** 나머지는 `GET /poster?variant=`에서 그때 그린다. 저장본의 origin을 쓰므로
  "안내물 다시 만들기"가 세 장 모두의 주소를 바꾼다. `store_posters`에 variant 행을 늘리지 말 것.
- 큰 글자(상호·제목·단계)는 번들한 주아체(`resources/fonts/Jua-Regular.ttf`, OFL), 작은 안내문은 `NanumSquareRound` → 맑은 고딕 → 기본 sans. 인쇄물 바탕이 밝은 톤으로 바뀌었다
  (예전엔 네이비 전면). 기존 매장의 저장본은 "안내물 다시 만들기"를 눌러야 새 디자인이 된다.
- 리뷰·별점은 어느 안내물에도 참여 조건으로 적지 않는다. 문구는 해요체.
- `CoreRulesTest`가 세 종류 모두 QR이 실제로 디코딩되는지 본다. 바탕색이나 장식을 바꾸면 이 테스트를 돌릴 것.

## 로그아웃

`features/admin/LogoutButton.tsx` 하나다. `/admin`, `AdminFrame`, `OperatorFrame` 세 헤더에 붙는다.

- `clearAdminSession()`이 JWT와 운영자 고정 만료 시각을 같이 지운다. 운영자 세션이 만료되면
  `/admin/operator/login`으로 이동해 이메일 OTP를 다시 요구한다.
- TanStack Query 캐시도 비운다. 안 비우면 다른 계정으로 로그인했을 때 잠깐 남의 매장이 보인다.
- `router.push`가 아니라 `window.location.assign`이다. 클라이언트 전환은 메모리 상태를 끌고 온다.

## 운영자 콘솔 (2026-09-22)

`/admin/operator` 아래 네 화면이다. `OperatorFrame.tsx`가 공통 껍데기이며 `AdminFrame`과 합치지
말 것(저쪽은 `storeId`가 반드시 있고 이쪽은 없다. 합치면 선택값이 되고 그 선택값을 잊은 화면이
다른 매장을 가리킨다).

| 경로 | 화면 |
|---|---|
| `/admin/operator` | 매장 심사 큐 (승인·거부·재심사·소유권 이전) |
| `/admin/operator/accounts` | 관리자 계정 목록, 운영자 권한 부여·회수, 운영자 신설 |
| `/admin/operator/resources` | 자원 현황 + 전화번호 해시 재계산 |
| `/admin/operator/audit` | 매장 심사와 계정 변경을 합친 활동 기록 |
| `/admin/operator/devices` | OTP·고정 세션 보안 상태와 만료 시각 |

- `Operators.java` — `OperatorAuditEvent`(append-only), `OperatorAccountService`,
  `OperatorAccountController`, `OperatorBootstrap`.
- 계정 사건을 `store_approval_events`에 끼워 넣지 말 것. 그쪽은 `store_id`가 NOT NULL이라
  매장 없는 사건을 표현할 수 없고, 억지로 매장을 붙이면 그 매장의 이력이 거짓이 된다.
- 감사 기록은 대상 이메일을 **사건 시점 값으로 동결**한다. FK만 두면 계정이 지워졌을 때
  "누구였는지"가 사라진다. 쿠폰이 상품명을 동결하는 것과 같은 이유다.
- 운영자 계정 신설은 **매장을 만들지 않는다.** 운영자가 어느 매장의 멤버가 되면 자기 매장을
  스스로 심사할 수 있고, 그 순간 승인 절차가 형식이 된다.
- 잠금 방지 두 가지를 없애지 말 것: 자기 자신 회수 금지(`OPERATOR_SELF_REVOKE`),
  마지막 운영자 회수 금지(`OPERATOR_LAST_ONE`). 둘 다 승인할 사람이 아무도 없는 상태를 만들고,
  그 상태를 되돌릴 API가 없어서 DB를 직접 고쳐야 한다.
- 활동 피드는 두 테이블을 메모리에서 합친다. UNION 뷰나 공통 상위 테이블을 만들지 말 것.
  각각 200건 상한이고 운영자 동작은 하루 수십 건 규모다.
- 테스트: `OperatorAccountTest.java` (신설·멱등 부여/회수·잠금 방지 두 경로·검색과 해시 비노출).

### 운영자 이메일 OTP와 고정 세션

- `SYSTEM_ADMIN`은 일반 `/api/admin/auth/login` 비밀번호 로그인을 사용할 수 없다.
- `/api/admin/operator-auth/request`가 등록 이메일로 6자리 OTP를 보내며 기본 유효시간은 120초다.
- OTP는 최대 5회 확인, 1회용이며 DB에는 토큰과 코드의 SHA-256만 저장한다.
- 성공하면 `session_type=OPERATOR_EMAIL_OTP`인 전용 JWT를 발급한다. 기본 수명은 600초다.
- 요청 활동으로 만료를 미루지 않고 refresh token도 없다. 만료 후 OTP부터 다시 로그인한다.
- 장시간 작업 때만 `OPERATOR_SESSION_TTL_SECONDS`를 배포 환경에서 바꾸고 작업 후 600으로 복구한다.
- `OperatorAccessFilter`는 `/api/admin/operator/**`에서 역할뿐 아니라 전용 세션 claim과 고정 만료를
  검증한다. 일반 관리자 JWT나 과거 운영자 비밀번호 JWT로 통과시키지 말 것.
- 운영자 계정 신설은 비밀번호를 받지 않는다. DB non-null 칸에는 로그인에 사용할 수 없는 무작위
  폐기 해시만 저장한다.
- 테스트: `OperatorOtpAuthTest.java`가 OTP 수명·1회성·10분 토큰·일반 비밀번호 로그인 차단을 확인한다.

### 첫 운영자 주입

운영자를 만드는 API는 운영자만 쓸 수 있어서 첫 한 명은 밖에서 넣는다. `Bootstrap`을 쓰지 않는
이유는 그쪽이 매장까지 만들기 때문이다.

```bash
sh scripts/create-operator.sh                      # operator@sodamlabs.kr
sh scripts/create-operator.sh admin@example.com    # 이메일 지정
```

스크립트는 이메일만 받아 `OPERATOR_BOOTSTRAP_EMAIL`로 첫 운영자를 만든다. 비밀번호를 생성하거나
출력하지 않는다. 운영자 로그인은 등록 이메일로 받는 OTP만 사용한다.

운영자가 **한 명이라도 있으면 아무 일도 하지 않는다.** 이메일이 아니라 역할 수로 보기 때문에,
운영자가 권한을 잃은 뒤 재기동에서 조용히 되돌아오는 일이 없다. 값은 로그에 찍지 않는다.
계정이 만들어지면 bootstrap 이메일 변수를 지우고 재기동한다. 이후 운영자는 화면에서 만든다.

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

## 운영 콘솔(Sodam Ops Console) 연동 기록

**2026-09-26 — 사용자 승인 작업.** 소담랩스 운영 콘솔(`C:\SodamLabs\ops-console`, Phase 2 관측)이 이 서비스의
앱 메트릭과 오류 로그를 모을 수 있게 prod 설정만 바꿨다. 로컬/field-test 동작은 그대로다.

- `application-prod.yml`: actuator를 **관리 포트 8081로 분리**(`management.server.port`), 노출은 `health,metrics`,
  `http.server.requests` p95 백분위 활성화, `logging.structured.format.console: logstash`(Spring Boot 내장 JSON 로그).
  API 포트 8080에는 prod에서 actuator가 없다.
- `SecurityConfig.java`: `/actuator/metrics`, `/actuator/metrics/**` permitAll. 보안 체인은 관리 포트에도 적용되며
  `ManagementPortTest`가 관리 포트 health·metrics 200, API 포트 actuator 비노출을 확인한다.
- `nginx/production.conf`: 외부 actuator는 `= /api/actuator/health`만 `backend:8081`로 프록시하고 나머지
  `/api/actuator/`는 404. 운영 콘솔 Phase 1이 이 health 경로를 1분마다 외부 점검하므로 지우지 말 것.
- `docker-compose.prod.yml`: backend `127.0.0.1:18080:8081`. **루프백에는 관리 포트만 게시한다.** API 포트 8080은
  게시하지 않으므로 이 통로로 nginx rate limit·client-IP 정책을 우회할 수 없다. 호스트 collector
  (`ops-console/collector/collect.sh`, `APP_METRICS_URL=http://127.0.0.1:18080/actuator/metrics`)가 읽는다.
  backend healthcheck(base의 TCP 8080 접속)는 API 포트 생존 확인이라 그대로 둔다.
- `scripts/verify-production.sh`: 외부 `/api/actuator/metrics` 404 확인, 8080이 루프백 포함 어디에도 게시되지 않았는지 확인.
  `10_DEPLOYMENT.md`, `09_SECURITY_AND_ABUSE.md`의 포트 문구를 위와 맞췄다.

왜: 외부 health 점검만으로는 느림·자원 고갈·반복 오류의 원인을 좁힐 수 없다(ops-console PHASE_2 문서).
외부 노출 변화: 없다. 외부 actuator는 오히려 health 하나로 줄었다.
로그 원문은 콘솔이 저장 전에 마스킹하지만, 개인정보를 로그에 찍지 않는 원칙(AGENTS.md §8)은 그대로다.

롤백: `management.server.port`·exposure·structured logging 줄, nginx의 health 전용 location(원래
`location /api/actuator/ { proxy_pass http://backend:8080/actuator/; ... }`), compose의 `ports` 블록을 되돌리고
재배포(`docker compose ... up -d`). 8081 분리만 되돌릴 때는 nginx 프록시 대상도 반드시 8080으로 함께 되돌린다.
collector 중지는 호스트의 `/etc/cron.d/sodam-ops` 삭제로 충분하다.

## 요금제 자동결제 (2026-09-27)

포트원 V2 빌링키 월 자동결제. 규칙 원문은 AGENTS.md, 계약은 `05_API_SPEC.md`에 있다. 구현 위치만 적는다.

- `Billing.java` — `PortOneClient`(REST: 빌링키 조회·삭제, 빌링키 결제, 결과 재조회), `BillingService`,
  `BillingRenewalScheduler`(매일 00:10 KST), `BillingController`(`/api/admin/stores/{id}/billing`).
- 결제 상태는 `store_subscriptions`의 nullable 칸(`billing_key`, `last_paid_at`, `next_billing_at`, `next_plan`,
  `auto_renew`, `renewal_failures`, `billing_lock_until`)과 `subscription_payments`(결제 한 건 = 한 행)에 있다.
- 이용 상태(`ServiceState`)는 저장하지 않고 `ServiceAccessPolicy`가 날짜에서 계산한다. 스케줄러가 늦어도 판정이
  밀리지 않는다. 손님 차단은 `StoreAccessService.activeQr`와 `CouponService.get/redeem`, 사장 차단은
  `ServiceAccessWebConfig` 인터셉터 한 곳이다. 새 사장 API를 만들어도 자동으로 막힌다.
- 2026-09-27 이전 매장은 `next_billing_at`이 비어 있어 결제·제한 대상이 아니다(사용자 결정). 현장 테스트 시드
  매장도 `exemptFromBilling`으로 뺀다. 안내 화면(`StoreIntro`, 대시보드 결제 안내)은 Codex가 만들었고
  오류 코드 `STORE_PAYMENT_REQUIRED` / `SUBSCRIPTION_PAYMENT_REQUIRED`, `serviceSuspended`로 연결된다.
- 화면: `app/admin/stores/[storeId]/plan/page.tsx`가 `@portone/browser-sdk/v2`로 결제창을 연다. 모바일은
  `redirectUrl`로 돌아와 `billingKey` 쿼리로 결제를 마저 한다.
- PG: 서버는 토스페이먼츠 + KG이니시스(카드)를 모두 지원하지만, **화면은 KG이니시스 단일**이다(2026-09-27 사용자 결정).
  `plan/page.tsx`의 billing 쿼리 `select`가 INICIS 채널만 남긴다. 토스를 다시 열 때는 그 한 줄만 지운다.
  NICE는 결제창 빌링키가 간편결제만이라 뺐다.
- 환경 변수 `PORTONE_API_SECRET`, `PORTONE_STORE_ID`, `PORTONE_TOSS_CHANNEL_KEY`, `PORTONE_INICIS_CHANNEL_KEY`.
  하나라도 비면 결제 버튼이 숨고 기존처럼 운영자 조작만 남는다. field-test 예시에는 공용 테스트 채널 키가 들어 있다.
- CSP에 `cdn.portone.io`(script)와 `*.iamport.co`·`service.iamport.kr`(connect/frame)를 열었다. 빼면 결제창이 안 뜬다.
- 테스트: `BillingTest.java` 14건(가짜 포트원 서버). 체험 종료일=첫 결제예정일, 체험 중 등록은 종료일 청구,
  결제일 교체, 남의 빌링키 거부, 대표만, 거절 시 무변경, 기간 중 내리기, 갱신 중복 청구 방지, D+2 유예 뒤 제한,
  기존 매장 비대상, 재결제로 해제, 사장·손님 차단 경로(MockMvc), 해지, 동시 결제 거부.

되돌리면 안 되는 지점:

- 포트원 호출을 `@Transactional` 안에 넣지 말 것. 쓰기는 `TransactionTemplate`으로 짧게 감싼다.
- PG를 부르기 전 PENDING 행을 남기는 순서를 바꾸지 말 것. 결제 후 우리 쪽 기록이 실패해도 대조할 흔적이 남는다.
- 갱신 paymentId(`renew-{storeId}-{paidThrough}-{failures}`)를 무작위로 바꾸지 말 것. 스케줄러가 두 번 돌아도
  포트원이 `ALREADY_PAID`로 막아 주는 것이 이 결정론 덕분이다.
- 빌링키 검증(`customer.id`, 채널)을 빼지 말 것. 다른 매장의 빌링키로 결제를 끼워 넣을 수 있다.
- 이용 제한을 저장된 상태 칸으로 판정하지 말 것. 날짜 계산 한 곳(`ServiceAccessPolicy`)이 정본이다.
- 손님 오류 문구에 결제 얘기를 넣지 말 것. 사장의 결제 문제를 손님에게 알리지 않는다.

아직 없는 것: 환불·부분취소 API(포트원 콘솔에서 처리), 웹훅(동기 응답과 재조회로 대신한다), PENDING 자동 대조.
