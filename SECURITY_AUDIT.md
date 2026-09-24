# SodamHanpan Security Audit

감사일: 2026-09-23 (Asia/Seoul)
대상: 현재 `main` 작업트리의 소담한판 전체 저장소
방법: 관리형 Deep Scan 도구가 현재 세션에 없어 수동/표준 다중 패스 정적 감사, 기존 보안 테스트 검토, 의존성 감사, 전체 테스트·빌드 및 Docker/Nginx 검증으로 대체했다.

## Executive Summary

현재 구현은 매장 membership 기반 tenant 격리, 운영자 role 검증, 서버 결정형 게임 결과, 게임/쿠폰 원자 생성, 쿠폰 사용 행 잠금, BCrypt, AES-256-GCM 개인정보 암호화, HMAC 조회, 공개 API rate limit, 일회용 회수 티켓 등 핵심 통제를 갖고 있다.

이번 감사에서는 운영 프로필의 보안 기능이 환경변수 누락 시 비활성화되는 HIGH 1건을 확인했다. 운영 Compose와 `prod` 프로필을 fail-closed로 바꿔 사업자 진위확인, 운영자 승인, 운영자 추가 접근통제가 기본적으로 켜지도록 수정했다. SMTP STARTTLS downgrade, Vitest 개발 의존성 경로 탐색, 환경파일의 build/Git 유입, root 컨테이너, Nginx 버전 노출도 최소 변경으로 조치했다.

확인된 등급은 CRITICAL 0, HIGH 1, MEDIUM 5, LOW 4, INFO 다수다. 코드로 조치한 항목은 HIGH 1, MEDIUM 2, LOW 3이다. 잔존 항목은 기존 JWT 즉시 폐기, 전화번호 기반 쿠폰 회수의 신원 증명 한계, Cloudflare 원본 우회 차단, GitHub Actions SHA pinning이며 운영·제품 결정을 포함한다.

## Architecture Overview

- Frontend: Next.js 15, React 19, TypeScript, TanStack Query, Zustand, React Three Fiber
- Backend: Java 17, Spring Boot 3.4.4, Spring Security, Spring Data JPA, JWT, JavaMail
- Database: PostgreSQL 17; 테스트는 H2 PostgreSQL mode
- Auth: Bearer JWT를 `sessionStorage`에 보관, 8시간 만료, stateless API
- Authorization: 매장별 membership 검사와 별도 `SYSTEM_ADMIN` role 검사
- Privacy: 고객 이름/전화번호 AES-GCM 암호화, 전화번호 HMAC 조회, last4 표시
- Infra: Docker Compose, Nginx 단일 ingress, Cloudflare proxy/Quick Tunnel 경로
- External API: 국세청 사업자 진위확인, 선택적 OpenAI API, SMTP
- Scheduler: 개인정보 정리, rate-counter/회수/운영자 challenge 정리, 주간 리포트
- 없음: 결제 PG, OAuth, 사용자 파일 업로드, WebSocket/SSE, Redis, 외부 queue

## Security Scope

애플리케이션 코드, 테스트, Gradle/npm manifest와 lockfile, Spring 설정, Dockerfile, Compose, Nginx, GitHub Actions, 스크립트, Git ignore/build context, 기존 보안 문서를 확인했다. 추적 파일 356개와 실행·설정 중심 inventory를 검색했으며 바이너리/PDF 내용은 실행 코드로 감사하지 않았다. 실제 production 계정, DB, DNS, Cloudflare 대시보드, TLS 인증서, 방화벽, GHCR/GitHub secrets에는 접근하지 않았다.

## Critical Findings

없음.

## High Findings

### H-01 운영 보안 통제 fail-open — FIXED

- 파일: `backend/src/main/resources/application-prod.yml`, `docker-compose.prod.yml`
- 취약점: 운영 Compose가 base 설정의 `BUSINESS_VERIFICATION_ENABLED=false`, `STORE_APPROVAL_REQUIRED=false`, `OPERATOR_ACCESS_ENABLED=false`를 상속했다.
- 위험도: HIGH
- 문제: 운영자가 환경변수를 빠뜨리면 임의 사업자번호로 생성한 매장이 즉시 ACTIVE가 되고, 운영자 콘솔의 추가 IP/WebAuthn 통제도 우회된다.
- 공격 시나리오: 공격자가 아직 등록되지 않은 실제 사업자번호를 선점해 활성 매장·QR·직원 PIN을 발급받고 해당 사업체를 가장한다.
- 수정 내용: `prod` 프로필 기본값과 production Compose를 모두 true로 고정하고 `NTS_SERVICE_KEY`가 없으면 Compose 해석 단계에서 실패하도록 했다.
- 기능 영향: 개발/field-test 기본값과 URL은 변경하지 않았다. 운영 배포에는 국세청 키와 운영자 접근 설정이 필수다.
- 검증 방법: 설정 회귀 테스트, base/prod `docker compose config --quiet`, 백엔드 전체 테스트.

## Medium Findings

### M-01 SMTP STARTTLS가 선택적 — FIXED

- 파일: `backend/src/main/java/com/yutreview/AdminAccountRecovery.java`, `backend/src/main/resources/application.yml`
- 위험: SMTP 서버/네트워크 downgrade 시 계정 복구 메일 자격증명이 암호화되지 않은 연결로 전송될 수 있었다.
- 수정: `mail.smtp.starttls.required=true`를 기본값으로 추가했다.
- 검증: JavaMail property 단위 테스트 및 백엔드 전체 테스트.

### M-02 Vitest mock redirect path traversal advisory — FIXED

- 파일: `frontend/package.json`, `frontend/package-lock.json`
- 위험: `vitest 3.2.7`/`@vitest/mocker 3.2.7`이 GHSA-82fw-gwwq-j7x9 영향 범위였다. 개발/CI 전용이지만 악성 테스트 입력이 파일을 읽을 수 있다.
- 수정: Vitest 4.1.11로 업데이트했다.
- 검증: `npm audit` 0건, frontend 19 tests, lint, type/build 성공.

### M-03 비밀번호 재설정 후 기존 JWT가 즉시 폐기되지 않음 — REMAINING

- 파일: `SecurityConfig.java`, `AdminAccountRecovery.java`
- 공격 시나리오: 탈취된 JWT는 사용자가 비밀번호를 재설정해도 원래 만료(최대 8시간)까지 유효하다.
- 권고: `tokenVersion` 또는 `passwordChangedAt`을 JWT claim/검증에 연결하고 재설정 시 증가시킨다. DB 변경과 모든 인증 요청의 조회 비용을 결정해야 하므로 이번 최소 패치에 포함하지 않았다.

### M-04 쿠폰 회수 티켓이 추가적인 소유 증명을 제공하지 않음 — REMAINING

- 파일: `PublicController.java`, `Recovery.java`
- 공격 시나리오: 매장 QR과 피해자 전화번호를 아는 공격자는 5분·1회용 티켓을 자신이 발급받아 즉시 교환할 수 있다. 직접 coupon token 반환보다는 노출 시간이 줄었지만 전화번호 단일 지식 요소라는 근본 경계는 남는다.
- 완화: 쿠폰 사용에는 매장 직원 PIN이 별도로 필요하며, 응답에 고객 이름/전화 원문은 없다.
- 권고: SMS를 MVP에 추가하지 않는 기존 결정 아래에서는 이름 일치, 현장 직원 승인, 또는 기존 브라우저 possession token 중 제품 UX와 맞는 추가 요소를 선택해야 한다.

### M-05 Cloudflare를 우회한 origin 직접 접근 가능성 — MANUAL ACTION REQUIRED

- 파일: `nginx/production.conf`, Lightsail/Cloudflare 설정
- 위험: Lightsail 443이 인터넷 전체에 열려 있으면 canonical Host를 보낸 직접 요청이 Cloudflare WAF/edge 제한을 우회할 수 있다. Nginx/백엔드 자체 제한은 남는다.
- 조치: Lightsail 방화벽을 Cloudflare origin 대역으로 제한하거나 Authenticated Origin Pulls/Cloudflare Tunnel을 적용하고 실제 연결을 검증한다.

## Low Findings

### L-01 환경파일 변형이 Git/Docker 경계에서 포괄적으로 제외되지 않음 — FIXED

`.env.*`를 무시하고 `*.example`만 허용했다. backend/frontend Docker context에서도 동일하게 제외했다. 현재 추적 파일에서 실제 credential signature는 발견되지 않았다.

### L-02 Backend/Frontend runtime container가 root — FIXED

Backend는 UID/GID 10001, frontend는 `node` 사용자로 실행한다. 빌드 결과의 이미지 사용자 값을 직접 검사했다.

### L-03 Nginx 버전 노출 — FIXED

field-test와 production 설정에 `server_tokens off`를 적용했다.

### L-04 GitHub Actions가 action tag를 사용 — REMAINING

`.github/workflows/publish-images.yml`은 최소 권한(`contents: read`, `packages: write`)을 사용하지만 third-party actions가 commit SHA가 아닌 major tag다. Dependabot/Renovate와 SHA pinning을 함께 적용하는 것이 좋다.

## Informational Findings

- JWT는 HMAC256 알고리즘을 코드에서 고정하고 32자 미만 secret으로 기동하지 않으며 만료를 검증한다. issuer/audience는 현재 단일 서비스 전용 키라는 전제에서 사용하지 않는다.
- CSRF 비활성화는 쿠키 인증이 아닌 명시적 Bearer header/stateless 구조와 일치한다. `credentials: include`는 현재 인증 cookie를 만들지 않는다.
- CORS allowlist를 별도 개방하지 않아 browser API는 same-origin 경계다.
- SQL/JPQL/native query는 상수 쿼리와 parameter binding을 사용했다. 공격자 입력 문자열 결합 sink를 찾지 못했다.
- React `dangerouslySetInnerHTML` 사용을 찾지 못했다. 사용자 문자열은 React text rendering을 거친다.
- 사용자 입력 URL을 서버가 fetch하는 SSRF 경로는 없다. 국세청 endpoint는 상수이고 OpenAI base URL은 server configuration이다.
- `Runtime.exec`, `ProcessBuilder`, application shell execution을 찾지 못했다.
- 파일 업로드/다운로드 path 입력 경로가 없다. QR poster 다운로드는 서버 생성 바이트와 안전한 attachment filename을 사용한다.
- 고객 개인정보·JWT·PIN·API key를 직접 기록하는 application logger를 찾지 못했다. 예외 응답은 stack/SQL을 노출하지 않는다.

## Fixed Issues

1. Production 사업자 검증·매장 승인·운영자 추가 통제 fail-closed
2. SMTP STARTTLS required
3. 취약 Vitest/모커 업데이트
4. `.env.*` Git/Docker 제외
5. Backend/Frontend non-root runtime
6. Nginx version suppression

## Remaining Issues

1. 비밀번호 재설정 시 기존 JWT 즉시 폐기
2. 전화번호 기반 쿠폰 recovery의 추가 possession proof
3. Cloudflare origin 우회 방화벽/인증
4. GitHub Actions commit SHA pinning
5. 실제 운영 secret entropy/rotation, DB 최소권한, backup 암호화·복구 시험은 미검증

## Manual Actions Required

- MANUAL ACTION REQUIRED: production secret들을 생성 스크립트로 새로 만들고 저장소 밖 secret store에 보관한다. 실제 값은 본 감사에서 출력하거나 변경하지 않았다.
- MANUAL ACTION REQUIRED: 과거 노출 가능성이 있다면 JWT, phone HMAC/encryption key, DB, SMTP, NTS/OpenAI 키를 영향 분석 후 회전한다.
- MANUAL ACTION REQUIRED: Cloudflare proxy/TLS, origin firewall, DNS, HSTS 응답을 외부 네트워크에서 확인한다.
- MANUAL ACTION REQUIRED: PostgreSQL 계정 권한, 외부 5432 차단, backup 암호화와 restore rehearsal을 확인한다.
- MANUAL ACTION REQUIRED: 운영자 WebAuthn 등록 및 비상 복구 절차를 실제 계정으로 검증한다.

## Dependency Risks

- Frontend: 수정 전 moderate 2건(Vitest 및 `@vitest/mocker`), 수정 후 `npm audit` 0건.
- Backend: Spring Boot 3.4.4, java-jwt 4.5.0, ZXing 3.5.3을 확인했다. 저장소에 OWASP Dependency-Check 플러그인이 없어 CVE feed 기반 백엔드 결과는 생성하지 못했다. 무근거 major upgrade는 하지 않았다.
- Docker base image digest 고정은 아직 적용되지 않았다. 정기 rebuild와 registry/image scanning이 필요하다.

## Infrastructure Risks

Production Compose는 DB/backend/frontend host port를 열지 않고 Nginx 80/443만 노출한다. canonical host fail-close, HTTPS redirect, HSTS, CSP, frame/nosniff/referrer/permissions headers, request body limit, Cloudflare real-IP allowlist가 존재한다. 실제 firewall·Cloudflare proxy·인증서 체인은 로컬 정적 검사만으로 입증할 수 없다.

## Authentication Review

로그인, 회원가입, 복구 request/verify, JWT 발급/검증을 추적했다. BCrypt, 로그인 IP+계정 제한, 복구 IP 제한, 10분/5회/1회용 code, token/code hash 저장을 확인했다. JWT query parameter 전달과 token logging은 찾지 못했다. logout은 client token 삭제 방식이라 server-side revoke는 없다.

## Authorization Review

모든 admin store API는 `StoreAccessService.member`를 통해 서버에서 membership을 검사한다. 운영자 API는 `SYSTEM_ADMIN`을 다시 조회·검증하며 추가 접근 filter가 존재한다. 요금제 변경도 운영자 전용이다. frontend route hiding은 권한 통제로 사용되지 않는다. 점검 범위에서 재현 가능한 IDOR/BOLA는 확인되지 않았다.

## API Security Review

DTO validation, page size 상한, store/game 일일·분당 quota, login/signup/PIN/recovery 제한, idempotency key, SecureRandom 결과, coupon pessimistic lock을 확인했다. 공개 bearer identifier는 충분한 난수 token/UUID를 사용한다. API error envelope는 내부 예외를 숨긴다.

## Frontend Security Review

관리자 JWT는 설계대로 `sessionStorage`에만 저장되고 logout/401 때 제거된다. frontend secret 환경변수는 없고 `NEXT_PUBLIC_*`는 공개 법적 메타데이터뿐이다. open redirect/dangerous HTML sink를 찾지 못했다. CSP는 R3F/WASM 동작을 위해 제한적으로 inline/WASM 실행을 허용한다.

## Backend Security Review

Spring Security는 공개 경로를 명시하고 나머지를 authenticated로 제한한다. JPA entity를 request body로 직접 받지 않는다. PII 암호화/HMAC, 개인정보 cleanup, AI context/history PII filtering, read-only store-bound AI tool을 확인했다. 외부 API URL은 사용자 입력이 아니다.

## Docker / Deployment Review

Compose에서 DB 직접 노출이 없고 production ingress 선택이 분리되어 있다. runtime image를 non-root로 변경했고 실제 image metadata가 backend `10001:10001`, frontend `node`임을 확인했다. `.env.*`는 build context에서 제외했다. base/prod Compose config와 두 Nginx 설정의 문법 검사가 성공했다.

## CI/CD Review

GitHub Actions는 main/tag/manual에서 이미지를 빌드해 GHCR에 push한다. permissions는 job 요구 범위로 제한되어 있고 untrusted PR에서 production credential을 사용하는 경로는 없다. action SHA pinning과 image signing/SBOM은 잔존 hardening이다.

## Secret Management Review

credential signature와 주요 secret keyword를 전수 검색했다. 추적 파일에서 실제 key signature는 확인되지 않았다. application/Compose는 secret을 환경변수로 받으며 JWT/phone keys는 기동 시 강도를 검사한다. 로컬 `.env.field-test`의 존재만 확인했고 값은 읽거나 출력하지 않았다.

## Test Results

- Backend: Docker Java 17에서 전체 `./gradlew test --no-daemon` PASS; 선언된 `@Test` 메서드 117개.
- Focused backend: account recovery 및 production security configuration PASS.
- Frontend: `npm run lint` PASS, `npm run test` PASS (3 files, 19 tests), `npm run build` PASS.
- Dependency: `npm audit` PASS, 0 vulnerabilities.
- Compose: base 및 production merged config PASS.
- Nginx: default 및 production `nginx -t` PASS.
- Host Gradle: Windows loopback 환경 오류로 실행 불가; 동일 checkout을 read-only mount한 Docker Java 17 경로로 대체했다.

## Build Results

- Backend production Docker image build PASS.
- Frontend production Docker image build PASS.
- Backend image runtime user: `10001:10001`.
- Frontend image runtime user: `node`.
- 실제 production 배포는 수행하지 않았다.

## Recommended Next Steps

1. 운영 배포 전에 필수 환경변수와 Cloudflare/Lightsail 경계를 staging에서 검증한다.
2. 비밀번호 재설정 시 JWT 즉시 폐기 설계를 별도 migration/test와 함께 구현한다.
3. 쿠폰 회수에 추가 possession proof를 선택하고 고객 UX 회귀 테스트를 한다.
4. GitHub Actions SHA pinning, SBOM, container vulnerability scan을 CI에 추가한다.
5. 외부 모바일·브라우저와 실제 SMTP/NTS 연동 E2E를 수행한다.

## Change History

각 수정은 위 finding의 파일·취약점·위험도·문제·공격 시나리오·수정 내용·기능 영향·검증 방법에 기록했다. 실제 secret 값, 운영 계정, 고객 데이터는 기록하지 않았다.
