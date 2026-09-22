# Claude용 보안 취약점 해결 구현 프롬프트

아래 작업을 `C:\Users\LeeHoYoung\IdeaProjects\yut-review`에서 수행하라.

## 반드시 먼저 읽을 파일

1. `AGENTS.md`
2. `docs/security/report.md`
3. `docs/security/findings.json`
4. `docs/security/coverage.json`
5. `docs/security/scan-manifest.json`
6. `docs/security-remediation-2026-09-21/보안점검_해결방안.md`
7. `docs/security-remediation-2026-09-21/` 아래 개별 보고서 10개
8. 관련 원본 문서: `09_SECURITY_AND_ABUSE.md`, `03_ARCHITECTURE.md`, `05_API_SPEC.md`, `10_DEPLOYMENT.md`, `12_LOCAL_FIELD_TEST.md`

## 작업 목표

정적 보안점검에서 확인된 10개 항목을 실제 코드·테스트·배포 설정에 반영하라. 기존 MVP 잠금 규칙을 변경하지 말라. 특히 QR 고객의 게임 진입, SMS 없는 고객 흐름, SecureRandom 결과 결정, 원자적 게임/쿠폰 처리, 매장 격리, 2일 cooldown, AI 비개인정보 원칙을 유지하라.

## 확정된 운영 결정

### 1. 고객 쿠폰 회수

전화번호 조회 응답에서 `couponToken`을 직접 반환하지 말라. 짧은 만료(권장 5분), 일회성, 매장·전화번호 hash·쿠폰 ID에 묶인 opaque recovery session을 발급하고 쿠폰 상세 조회는 해당 세션으로만 허용하라. SMS 인증은 추가하지 않는다. 세션 ID와 possession-bound secret을 서버에서 검증하고, 만료·재사용·타 매장 사용을 거부하라. 기존 직접 token 조회 경로는 내부 전용으로 축소하거나 동일 보호를 적용하라.

### 2. 사업자 가입

사업자등록번호 중복 가입 금지와 운영자(소담랩스/이호영) 승인 후 가입 완료 흐름을 유지·강화하라. 가입 요청은 `PENDING_APPROVAL` 상태로 저장하고 운영자 승인 전에는 OWNER 권한, QR 활성화, PIN 사용, 포스터 공개를 허용하지 말라. 승인·거부·재심사·소유권 변경을 감사 로그로 남기고 운영자 인증과 CSRF/재전송 방어를 적용하라. 이미 승인된 기존 매장의 동작을 깨지 않는 migration을 작성하라.

### 3. 공개 요청 제한 — 기준을 다음과 같이 고정

Lightsail 2GB 단일 서버에서 동작해야 하므로 Redis를 새로 도입하지 말라. PostgreSQL 기반의 짧은 TTL rate-limit/카운터 또는 기존 DB를 사용하라.

- signup: 동일 IP 1시간 5회, 24시간 20회; 동일 사업자등록번호 24시간 3회; 초과 시 `429`
- game create: 동일 매장 1분 30회, 동일 IP 1분 10회; 동일 phone hash는 기존 2일 cooldown이 authoritative
- login: IP 1분 5회 + 계정 1분 5회, 성공 시 해당 키 초기화
- staff PIN: `storeId + IP` 1분 10회와 `storeId + couponId` 1분 5회
- 모든 counter는 TTL과 최대 cardinality/정리 정책을 가져야 하며, 프록시 뒤에서 전체 사용자를 하나의 IP로 취급하지 않도록 한다.
- rate-limit은 정상 QR 게임 경험을 막지 않도록 매장별 quota와 고객별 cooldown을 구분한다.

### 4. 배포 환경

운영 목표는 **AWS Lightsail 2GB 단일 VM + Cloudflare Free 프록시**다.

- 외부 공개 포트는 80/443만 허용하고 PostgreSQL, Spring Boot, Next.js 포트는 host에 publish하지 말라.
- Cloudflare에서 HTTPS를 사용하고 origin은 Cloudflare Origin Certificate 또는 검증 가능한 TLS로 구성한다. Cloudflare SSL mode는 `Full (strict)`로 한다.
- Nginx가 유일한 ingress가 되게 하고 production 설정을 명시적으로 선택한다. default/field-test HTTP 설정으로 운영되지 않게 fail-closed 한다.
- canonical origin은 `https://hanpan.sodamlabs.kr`로 고정한다. 요청 Host로 QR/포스터 URL을 만들지 말라.
- Cloudflare의 검증된 connecting-IP 헤더는 신뢰된 Cloudflare IP 범위에서만 해석하고, 그 외에는 socket peer를 사용한다. Nginx와 Spring의 client-IP 정책을 일치시킨다.
- HSTS, CSP, frame/mime/referrer/permissions headers, HTTP→HTTPS redirect를 운영 경로에서 검증한다.
- Quick Tunnel은 개발/현장 테스트 전용이며 production 설정에 섞지 말라.
- Lightsail 2GB에 맞춰 이미지·로그·포스터 저장량에 상한과 정리 정책을 둔다.

### 5. AI 이력 보호

클라이언트가 보낸 chat history를 신뢰하지 말라. 서버가 보유한 제한된 대화 이력만 사용하고, 각 user/assistant turn을 동일한 중앙 PII·secret 필터로 처리한 뒤 LLM payload를 만든다. phone, phoneHash, phoneLast4 조합, coupon token, JWT, PIN, API key, 이메일·전화번호 패턴을 차단한다. 필터 실패 시 provider 호출을 중단하고 감사 로그에는 값 대신 코드만 남긴다. AI는 관리자 전용·aggregate/read-only/store-bound이며 고객 API와 게임 흐름에 연결하지 않는다.

### 6. 전화번호 HMAC 키

`PHONE_HMAC_SECRET`는 Base64로 인코딩한 무작위 32바이트 이상만 허용하고 startup에서 형식·길이 검증 실패 시 종료하라. 기존 hash와의 호환을 위해 `current`/`previous` 키를 한시적으로 지원하는 마이그레이션 전략을 설계하고, 전체 phone hash 재계산 완료 후 이전 키를 폐기하라. 키 값은 로그·에러·응답에 절대 노출하지 말라. 실제 secret 값은 생성하거나 커밋하지 말고 운영자가 주입하도록 문서화하라.

## 나머지 findings도 해결

- 로그인 limiter: TTL, 최대 엔트리, 주기적 purge를 보장하는 bounded cache로 변경
- CSV: `=,+,-,@`, tab, CR 등 수식 선행문자를 spreadsheet-safe 방식으로 중화한 뒤 quoting; 회귀 테스트 추가
- 공개 게임/가입 자원고갈: 위 quota 외에 DB row/스토리지 상한, 429, 부하 테스트와 운영 모니터링 추가

## 구현 규칙

1. 소스 수정 전 현재 코드를 추적하고 unrelated 변경을 덮어쓰지 말라.
2. 공개 API 계약을 바꾸면 `05_API_SPEC.md`, 테스트, 프론트 호출부를 함께 갱신하라.
3. migration은 기존 승인 매장·기존 쿠폰·기존 고객 cooldown을 보존해야 한다.
4. 민감값을 테스트 fixture·로그·스크린샷·커밋에 넣지 말라.
5. 새 Redis/외부 SaaS/유료 서비스는 도입하지 말라.

## 필수 검증

- backend: `./gradlew test`
- frontend: `package.json`에 존재하는 `lint`, `test`, `build`
- recovery session: 만료·재사용·타 매장·직접 coupon token 접근 거부
- signup: 중복 사업자번호, 승인 전 권한, 승인/거부/재시도
- rate-limit: 각 기준의 경계값과 TTL purge, Cloudflare forwarded IP 시뮬레이션
- game: 반복 idempotency, store isolation, cooldown, 원자적 coupon 발급
- AI: history의 PII/secret이 provider payload에 나타나지 않는 테스트
- CSV: formula trigger 전체 테스트
- HMAC: 약한 키 startup 실패, 강한 Base64 키 성공, rotation/migration
- Docker: Lightsail과 동일한 production Compose build, 80/443 ingress, backend/DB host 비공개, health check

## 완료 보고 형식

최종 응답에는 다음을 명확히 적어라.

- 수정한 파일과 migration 목록
- 실행한 명령과 통과/실패 결과
- 아직 검증하지 못한 Cloudflare DNS, Lightsail firewall, 실제 TLS certificate, 운영 secret 주입 항목
- 배포 전 rollback 절차
- 보안 취약점별 해결 여부를 `완료 / 부분완료 / 미완료`로 표시

테스트가 실패하면 숨기지 말고 원인과 남은 조치를 적어라. 모든 runtime·배포 검증 전에는 보안점검 해결 완료라고 선언하지 말라.
