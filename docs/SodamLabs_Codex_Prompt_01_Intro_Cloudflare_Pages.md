# Codex Prompt — SodamLabs Intro / Cloudflare Pages Production Readiness

## ROLE

너는 SodamLabs 공식 인트로 웹사이트의 Senior Frontend / DevOps Engineer다.

대상 프로젝트는:

```text
sodamlabs-web
```

이며 최종 배포 대상은:

```text
https://sodamlabs.kr
```

이다.

배포 방식은 AWS가 아니라 **Cloudflare Pages**를 사용한다.

---

# 목표

현재 프로젝트를 분석하고 다음을 만족하도록 수정한다.

- 정적 배포 가능한 구조
- `npm run build` 성공
- Cloudflare Pages 배포 호환
- `sodamlabs.kr` custom domain 적용 가능
- 모바일/데스크톱 반응형
- Yut Review / ReviewPilot 서비스 링크
- Sodam은 Coming Soon
- SEO 기본 구성
- secret이 frontend bundle에 포함되지 않음

---

# 절대 원칙

기능과 콘텐츠를 무작정 재설계하지 않는다.

먼저 현재 프로젝트 구조를 분석한다.

다음 파일을 우선 조사한다.

```text
package.json
vite.config.*
src/**
public/**
.env*
.gitignore
tsconfig*
router 관련 파일
```

구현 전에 반드시 다음 형식으로 보고한다.

```text
[SODAMLABS INTRO DEPLOYMENT AUDIT]

Framework:
Build tool:
Node requirement:
Build command:
Output directory:
Current routes:
Environment variables:
Potential secrets:
Current service URLs:
Cloudflare Pages blockers:
Required changes:
```

그 후 수정한다.

---

# Cloudflare Pages 기준

React + Vite라면 기본값:

```text
Production branch: main
Build command: npm run build
Build output directory: dist
```

현재 프로젝트가 다른 framework라면 실제 구조를 기준으로 적절한 값을 결정한다.

---

# package.json

다음을 검토한다.

- `build` script 존재 여부
- build가 production에서 동작하는지
- 필요하면 Node engines 지정
- 불필요한 dev-only package가 production build를 깨지 않는지

예:

```json
{
  "engines": {
    "node": ">=20"
  }
}
```

단 현재 프로젝트와 호환되는 버전을 사용한다.

---

# 환경변수

브라우저 bundle에 들어가는 환경변수에는 secret을 넣지 않는다.

절대 포함 금지:

```text
AWS_SECRET_ACCESS_KEY
DB_PASSWORD
OPENAI_API_KEY
DATAAPI_SECRET
JWT_SIGNING_SECRET
PAYMENT_SECRET
```

Public URL 정도만 허용한다.

예:

```env
VITE_YUT_REVIEW_URL=https://yut.sodamlabs.kr
VITE_REVIEW_PILOT_URL=https://review.sodamlabs.kr
```

단 현재 구현이 단순 상수로 충분하면 굳이 env를 늘리지 않는다.

---

# 서비스 링크

최종 서비스 연결:

```text
Yut Review
→ https://yut.sodamlabs.kr

ReviewPilot
→ https://review.sodamlabs.kr

Sodam
→ Coming Soon
```

아직 배포되지 않은 서비스는 dead link 대신 Coming Soon 상태를 사용한다.

---

# Routing

가능하면 회사 인트로는 다음과 같이 단순하게 유지한다.

```text
/
#products
#about
#contact
```

React Router를 이미 사용하고 있다면 Cloudflare Pages에서 direct access / refresh 시 404가 발생하지 않는지 확인한다.

현재 SPA 구조를 유지해야 한다면 Pages에 필요한 fallback 처리 방법을 문서화한다.

---

# SEO

최소 다음을 구성한다.

```text
title
meta description
canonical
OpenGraph
favicon
robots.txt
sitemap.xml
```

Canonical:

```text
https://sodamlabs.kr/
```

서비스와 회사 관계가 명확해야 한다.

```text
SodamLabs
├── Yut Review
├── ReviewPilot
└── Sodam
```

---

# 품질

다음 환경을 점검한다.

```text
iPhone
Android
Tablet
Desktop
```

확인:

- horizontal overflow 없음
- 이미지 깨짐 없음
- 폰트 로딩 오류 없음
- console error 없음
- CLS 과도하지 않음
- 접근성 기본 준수
- 외부 링크 정상

---

# Git / 배포 안전성

`.gitignore` 확인:

```gitignore
node_modules/
dist/
.env
.env.*
!.env.example
```

실제 secret이 repository에 들어가 있으면 제거하고 사용자에게 알린다.

기존 Git history의 secret까지 자동 제거하지 않는다.
발견 시 별도 조치가 필요하다고 보고한다.

---

# Cloudflare 배포 문서 추가

다음 문서를 생성한다.

```text
docs/cloudflare-pages-deployment.md
```

포함 내용:

- build command
- output directory
- Node version
- Cloudflare Pages 프로젝트 생성법
- GitHub 연결법
- `sodamlabs.kr` custom domain 연결법
- 배포 실패 시 확인 항목
- rollback 방법
- 서비스 URL 변경 위치

---

# 검증

반드시 실행:

```bash
npm install
npm run build
```

가능하면:

```bash
npm run lint
npm test
```

현재 프로젝트에 script가 존재할 때만 실행한다.

---

# 최종 보고

1. Framework
2. Build output
3. 변경 파일
4. 추가 환경변수
5. Secret 노출 점검 결과
6. 서비스 링크 상태
7. SEO 적용 항목
8. Cloudflare Pages 설정값
9. 테스트 결과
10. 남은 TODO
