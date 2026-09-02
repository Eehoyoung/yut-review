# 리뷰이벤트 윷놀이 서비스 MVP 개발 문서

## 프로젝트 개요
오프라인 매장에서 네이버 플레이스 리뷰 작성 여부를 직원이 확인한 뒤, 고객이 매장 전용 QR을 통해 웹 윷놀이에 참여하고 차등 상품을 지급받는 서비스다.

## 확정 MVP 정책
- 매장별 고유 QR
- 고객 입력: 이름, 전화번호
- SMS 인증 없음
- 직원 PIN: 매장별 고유 6자리 숫자 난수
- 직원 PIN 사용처: 쿠폰 사용 처리 (게임 시작에는 직원 확인이 필요 없다)
- 참여 제한: `매장 + 전화번호` 기준 2일 쿨타임
- 쿨타임은 캘린더 날짜 기준: 9/2 참여 → 9/4 00:00 재참여 가능
- 미사용 쿠폰이 있으면 신규 게임보다 기존 쿠폰 화면 우선
- 확률: 매장이 결과별 가중치로 직접 설정 (기본값 도 32.5%, 개 32.5%, 걸 12.5%, 윷 12.5%, 모 10%)
- 상품: 매장별 1~5등급 (기본 3등급, 관리자에서 3/4/5등급 프리셋 제공)
- 등급 표기는 rank 정수이며 1이 1등
- 확률은 고객 화면의 상품 목록에도 표시한다
- 상품 수량 제한 없음
- 사용정책: 당일 / 다음날 / 둘 다 허용
- 쿠폰 유효기간: 발급일 기준 90일
- 대표 계정 1개가 여러 매장 관리 가능
- 매장 대표가 직접 회원가입해 매장을 등록할 수 있고, 운영자도 직접 등록할 수 있다
- MVP 리뷰 플랫폼: 네이버 플레이스
- 향후 Solapi 카카오 알림톡 연동 예정
- 윷 연출: 모바일 게임형 60% + 프리미엄 미니멀형 40%

## 추천 기술 스택
### Frontend
- Next.js
- React
- TypeScript
- Tailwind CSS
- TanStack Query
- Zustand
- Three.js / React Three Fiber / Drei / Rapier

### Backend
- Java 17
- Spring Boot 3.x
- Spring Security
- Spring Data JPA
- QueryDSL
- JWT
- Gradle

### Infra
- PostgreSQL 17
- Docker
- Nginx
- AWS EC2
- S3 + CloudFront
- Redis는 2차 단계

## 문서 목록
- `AGENTS.md` Codex 루트 개발 지침
- `01_PRD.md` 제품 요구사항
- `02_USER_FLOW.md` 고객/직원/대표 흐름
- `03_ARCHITECTURE.md` 시스템 구조
- `04_ERD.md` DB/ERD
- `05_API_SPEC.md` REST API 명세
- `06_BACKEND_GUIDE.md` Spring 구현 가이드
- `07_FRONTEND_GUIDE.md` Next.js 구현 가이드
- `08_YUT_3D_ANIMATION_SPEC.md` 3D 윷 애니메이션 명세
- `09_SECURITY_AND_ABUSE.md` 보안/어뷰징
- `10_DEPLOYMENT.md` 배포
- `11_MVP_PLAN.md`
- `12_LOCAL_FIELD_TEST.md` : 로컬 노트북 Docker + Cloudflare Quick Tunnel 현장 실테스트 개발 순서

## 최우선 구현 원칙
1. 게임 결과는 클라이언트가 아니라 서버가 먼저 결정한다.
2. 새로고침/재호출로 재추첨되지 않는다.
3. 모든 주요 데이터는 `store_id`로 매장을 분리한다.
4. 쿠폰은 발급뿐 아니라 사용 상태까지 서버에서 관리한다.
5. 쿠폰 사용 처리는 직원 PIN 검증 없이는 불가능하다.


## Codex Goal

- `작업지시.json` : Codex `/goal` 실행 매니페스트

## 로컬 현장 테스트 실행

Java 17 기반 백엔드와 PostgreSQL 17, Next.js, Nginx를 Docker Compose로 실행한다. DB·백엔드·프런트엔드는 호스트 포트를 열지 않으며 Nginx만 기본 `8088` 포트를 사용한다.

```powershell
Copy-Item .env.field-test.example .env.field-test
# .env.field-test의 비밀번호와 secret을 임의 값으로 변경
docker compose --env-file .env.field-test up -d --build
Invoke-WebRequest http://localhost:8088/api/actuator/health
```

관리자 주소는 `http://localhost:8088/admin/login`이다. 환경 파일의 관리자 계정으로 로그인하고, QR 화면에서 현재 origin 기반 고객 QR을 생성한다.

외부 휴대폰 테스트는 다음 명령으로 Quick Tunnel을 추가한다.

```powershell
docker compose --env-file .env.field-test --profile field-test up -d
docker compose logs -f cloudflared
```

로그의 임시 `https://*.trycloudflare.com` 주소로 관리자 페이지를 다시 열어 QR을 재생성한다. Tunnel 재시작 후 주소가 바뀌면 기존 테스트 QR도 다시 생성해야 한다. Quick Tunnel은 현장 테스트 전용이며, 실제 `.env.field-test`는 커밋하지 않는다.
