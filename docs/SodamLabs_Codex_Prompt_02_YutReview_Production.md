# Codex Prompt — Yut Review Production Docker / Health / Security

## ROLE

너는 Yut Review의 Senior Backend / DevOps Engineer다.

목표 인프라:

```text
Cloudflare
→ yut.sodamlabs.kr
→ Lightsail Static IP
→ Ubuntu 2GB
→ Nginx
→ Docker Compose
→ Yut Review App
→ DB
→ Redis (현재 실제 사용 중일 경우)
```

Yut Review는 초기에는 App / DB / Redis를 동일 Lightsail 인스턴스에서 운영한다.

---

# 가장 중요한 원칙

현재 구현 기능을 깨뜨리지 않는다.

특히 다음 기능은 보존한다.

- QR 진입
- 리뷰 인증
- 윷놀이
- 당첨/상품 처리
- 고객 1일 1회 정책
- 직원 PIN
- 사용 처리
- 관리자 기능

먼저 프로젝트 전체를 분석한 후 변경한다.

---

# 구현 전 분석

다음 항목을 조사한다.

```text
backend framework
frontend framework
DB 종류
Redis 사용 여부
현재 Dockerfile
현재 docker-compose
application port
health endpoint
file upload/storage
environment variables
production profile
CORS
cookie/session/JWT
absolute URL
localhost hardcoding
DB migration
```

보고 형식:

```text
[YUT REVIEW PRODUCTION AUDIT]

Framework:
Application port:
Database:
Redis:
Docker status:
Current env handling:
Health endpoint:
Production blockers:
Security risks:
Required code changes:
Required infra-only changes:
```

그 후 수정한다.

---

# Production Profile

개발/프로덕션 설정을 분리한다.

예:

```text
development
production
```

Spring이면 기존 profile 구조에 맞춰:

```text
application.yml
application-prod.yml
```

등을 검토한다.

Node 계열이면 현재 env loader를 사용한다.

코드에 production secret을 하드코딩하지 않는다.

---

# 환경변수

최소 검토 대상:

```text
APP_ENV
APP_PORT
DB_HOST
DB_PORT
DB_NAME
DB_USER
DB_PASSWORD
REDIS_HOST
REDIS_PORT
JWT_SECRET
COOKIE_SECURE
CORS_ALLOWED_ORIGINS
PUBLIC_BASE_URL
```

실제 프로젝트에서 필요한 값만 사용한다.

Production URL:

```text
https://yut.sodamlabs.kr
```

---

# Dockerfile

현재 Dockerfile이 있으면 재사용/개선한다.

목표:

- multi-stage build 가능 시 적용
- production dependency만 포함
- non-root 실행 가능 여부 검토
- healthcheck 지원
- secret bake-in 금지
- build cache 최적화
- 불필요 파일 제외

`.dockerignore` 검토:

```text
.git
node_modules
build
dist
.env
logs
tmp
```

실제 구조에 맞게 작성한다.

---

# docker-compose.prod.yml

Production 전용 compose를 생성/정리한다.

기본 원칙:

```text
app
db
redis (실사용 시)
```

App만 localhost에 publish한다.

예:

```yaml
ports:
  - "127.0.0.1:18080:8080"
```

DB/Redis에는 public `ports:`를 설정하지 않는다.

금지:

```yaml
db:
  ports:
    - "5432:5432"

redis:
  ports:
    - "6379:6379"
```

DB/Redis는 Docker network 내부에서만 사용한다.

---

# Persistent Volume

DB와 Redis 데이터는 container filesystem에만 두지 않는다.

Named volume 사용.

예:

```text
postgres-data
redis-data
```

컨테이너 재생성으로 데이터가 삭제되지 않아야 한다.

---

# Health Endpoint

Nginx/운영 점검을 위해 가벼운 health endpoint를 제공한다.

예:

```text
/health
```

응답:

```json
{"status":"UP"}
```

민감정보를 노출하지 않는다.

금지:

- DB password
- 내부 hostname
- stack trace
- environment dump

DB dependency까지 검사할지 여부는 현재 구조를 보고 결정하고,
liveness와 readiness를 구분하는 것이 유리하면 분리한다.

---

# Reverse Proxy Awareness

Cloudflare + Nginx 뒤에서 실행된다.

다음을 점검한다.

```text
X-Forwarded-For
X-Forwarded-Proto
Host
HTTPS redirect
secure cookie
```

애플리케이션이 proxy 뒤에서 HTTPS를 HTTP로 오인하지 않도록 현재 framework 설정을 검토한다.

---

# CORS

Production에서 광범위한:

```text
*
```

허용을 피한다.

필요한 origin만 허용한다.

기본:

```text
https://yut.sodamlabs.kr
```

별도 admin origin이 실제 존재하면 함께 반영한다.

---

# Cookie / Session

HTTPS production이면:

```text
Secure
HttpOnly
SameSite
```

설정을 검토한다.

OAuth/외부 인증이 있다면 SameSite 설정을 기능을 깨뜨리지 않도록 판단한다.

---

# Database

현재 DB engine을 그대로 사용한다.

DB host는 Docker 내부 service name을 사용한다.

예:

```text
db
```

절대 production code에:

```text
localhost:5432
127.0.0.1:5432
PUBLIC_IP:5432
```

를 하드코딩하지 않는다.

---

# Redis

실제로 Redis를 사용 중일 때만 compose에 포함한다.

단순히 "운영이면 Redis가 있어야 한다"는 이유로 새 dependency를 추가하지 않는다.

Redis를 쓴다면:

```text
redis:6379
```

Docker internal network로만 접근한다.

---

# Graceful Shutdown

Docker 재배포 시 요청 유실을 줄이기 위해 framework의 graceful shutdown 지원을 확인한다.

필요하면 적용한다.

---

# Logging

stdout/stderr 중심으로 구성한다.

log에 절대 남기지 않는다.

- password
- token
- PIN 원문
- session secret
- 개인정보
- 결제정보

Docker log rotation 또는 운영 문서에 rotation 방식을 명시한다.

---

# Backup Compatibility

S3 자동백업 script에서 사용할 수 있도록 다음을 명확히 한다.

```text
DB container service name
DB engine
DB name
DB user
volume name
```

다음 문서를 생성한다.

```text
docs/production-deployment.md
```

포함:

- build
- compose up/down
- env 목록
- health check
- backup 대상
- restore 주의사항
- rollback
- logs 확인법

---

# Commands 검증

프로젝트에 맞게 실제로 실행한다.

예:

```bash
docker compose -f docker-compose.prod.yml config
docker compose -f docker-compose.prod.yml build
```

기존 test가 있으면 수행한다.

---

# 최종 보고

1. 기존 구조
2. Production blockers
3. 변경 파일
4. Dockerfile 변경
5. Compose 구성
6. DB public exposure 여부
7. Redis public exposure 여부
8. health endpoint
9. production env
10. CORS/cookie 변경
11. backup 호환정보
12. 테스트 결과
13. 배포 명령
14. rollback
