# Codex Prompt — ReviewPilot Production / Two App Nodes / Load Balancer

## ROLE

너는 ReviewPilot 프로덕션의 Senior Backend / Platform Engineer다.

목표 구조:

```text
Cloudflare
→ review.sodamlabs.kr
→ Lightsail Load Balancer
→ ReviewPilot App-A 4GB
→ ReviewPilot App-B 4GB
→ Managed PostgreSQL HA
```

DataAPI Egress 5-IP 구조는 별도 문서의 요구사항도 함께 적용한다.

---

# 가장 중요한 원칙

기존 비즈니스 기능을 깨뜨리지 않는다.

먼저 전체 repository를 분석한다.

특히 조사:

```text
Dockerfile
docker-compose
DB engine
DB migration
scheduler
batch jobs
async workers
HTTP clients
DataAPI integration
OpenAI/LLM integration
authentication
cookies
CORS
health endpoints
file storage
local disk dependencies
```

구현 전:

```text
[REVIEWPILOT PRODUCTION AUDIT]

Framework:
App port:
DB:
Current scheduler:
Current workers:
Session/auth:
Local filesystem dependency:
Current Docker:
Current health:
LB readiness:
Horizontal scaling blockers:
Required changes:
```

---

# Horizontal Scaling

App-A/App-B 두 인스턴스가 동시에 실행되어도 정상이어야 한다.

다음을 금지한다.

- local memory에만 중요한 session/state 저장
- 서버 로컬 filesystem에만 중요한 persistent file 저장
- scheduler 중복 실행
- local-only queue

현재 구현을 분석하고 필요한 부분만 변경한다.

---

# Session / Authentication

두 App 노드 뒤에서 로그인 세션이 유지되어야 한다.

JWT stateless라면 기존 구조를 유지한다.

서버 메모리 session을 사용 중이라면
LB sticky session에 의존하기 전에 공유 session store 또는 stateless 구조 필요성을 분석한다.

임의로 auth 시스템 전체를 교체하지 않는다.

---

# Database

Production은 Managed PostgreSQL HA를 사용한다.

DB connection 정보는 환경변수로 받는다.

예:

```text
DB_HOST
DB_PORT
DB_NAME
DB_USER
DB_PASSWORD
```

App Docker 안에 production PostgreSQL을 띄우지 않는다.

---

# DB Connection Pool

App 인스턴스가 2대가 되므로 connection pool 총량을 고려한다.

예:

```text
App-A pool max 10
App-B pool max 10
Total 20
```

실제 DB plan과 framework 기본값을 조사해 적절히 설정한다.

하드코딩 대신 config/env를 우선한다.

---

# Health Endpoint

LB가 사용할 health endpoint를 제공한다.

예:

```text
/health
```

또는 현재 Spring Boot Actuator가 있다면:

```text
/actuator/health
```

민감정보는 노출하지 않는다.

LB health check가 빠르고 안정적으로 응답해야 한다.

---

# Scheduler Duplicate Prevention

App-A/App-B가 같은 scheduler를 동시에 실행하지 않아야 한다.

현재 확정 업무:

```text
리뷰 조회: 하루 1회
플랫폼별 조회: 매장 1개 × 플랫폼 1개 = API 1회
평균 플랫폼 수: 매장당 3개

답글 게시: 하루 1회 batch
실제 DataAPI 등록 소비: 리뷰 1건당 1회
평균 리뷰: 매장당 하루 4건
```

200개 매장 예상:

```text
조회 600/day
등록 800/day
```

중복 실행 방지를 위해 PostgreSQL 기반 distributed lock을 우선 검토한다.

Spring이면:

```text
ShedLock + PostgreSQL
```

등 현재 프로젝트와 가장 자연스러운 방법을 선택한다.

Redis를 scheduler lock 하나 때문에 새로 추가하지 않는다.

---

# Scheduler Spread

전체 매장을 동시에 호출하지 않는다.

예:

```text
조회 window:
03:00 ~ 03:30

게시:
04:00 이후 제한 concurrency
```

운영시간은 config로 변경 가능해야 한다.

예:

```text
REVIEW_FETCH_CRON
REVIEW_FETCH_SPREAD_MINUTES
REPLY_PUBLISH_CRON
```

---

# Production Docker

App-A / App-B 모두 동일 image/config를 사용한다.

차이는 필요한 경우:

```text
INSTANCE_ID=app-01
INSTANCE_ID=app-02
```

정도만 둔다.

이미지는 deterministic하게 빌드 가능해야 한다.

---

# No Local Persistent Storage

중요 파일을:

```text
./uploads
/tmp/important
local sqlite
```

등 App 서버 로컬 디스크에만 저장하면
App-A/App-B 간 불일치가 발생한다.

현재 이런 의존성이 있는지 조사한다.

필요하면 외부 object storage 도입 필요성을 보고한다.

사용자가 요구하지 않은 상태에서 대규모 저장소 migration을 임의로 수행하지 않는다.

---

# Proxy Headers / HTTPS

Cloudflare + Lightsail LB 뒤에서 동작한다.

다음 처리 검토:

```text
X-Forwarded-For
X-Forwarded-Proto
Host
secure cookie
redirect URL
OAuth callback
```

Production public URL:

```text
https://review.sodamlabs.kr
```

---

# CORS

허용 origin을 production domain 중심으로 제한한다.

임의의 `*` 사용을 피한다.

---

# Logging

structured log 권장.

공통 포함:

```text
requestId
instanceId
endpoint
latency
status
```

DataAPI 요청 로그는 별도 Egress prompt 규칙을 따른다.

secret/개인정보를 남기지 않는다.

---

# Deployment Documentation

생성:

```text
docs/production-deployment.md
```

포함:

- App-A/App-B 배포
- LB health check
- env
- DB 연결
- scheduler lock
- rolling deployment 순서
- rollback
- 장애 시 한 인스턴스 drain/remove 방법

---

# 검증

최소:

1. 두 App 동시 기동
2. 동일 DB 사용
3. 로그인/인증 정상
4. scheduler 한 번만 실행
5. health endpoint 정상
6. App-A 중단 시 App-B 정상
7. App-B 중단 시 App-A 정상
8. DataAPI 외 기능 정상
9. build/test 성공

---

# 최종 보고

1. horizontal scaling blocker
2. 수정 파일
3. scheduler lock
4. DB pool
5. session/auth 상태
6. local state 제거 여부
7. health endpoint
8. env
9. Docker
10. rolling deploy
11. 테스트 결과
12. remaining risks
