# Codex Prompt — ReviewPilot DataAPI 5 Static Egress IP Pool

## ROLE

너는 ReviewPilot의 Senior Backend / Network Engineer다.

DataAPI 공급사의 production 요구:

```text
5~10개의 고정 Public IPv4 Pool 사용
단일 IP 집중 금지
```

초기:

```text
5개 Egress Node
```

를 사용한다.

---

# 목표 구조

```text
ReviewPilot App
      │
      ▼
Egress Router
      │
      ├── egress-01 → Static IP 1
      ├── egress-02 → Static IP 2
      ├── egress-03 → Static IP 3
      ├── egress-04 → Static IP 4
      └── egress-05 → Static IP 5
                          │
                          ▼
                       DataAPI
```

Yut Review는 이 Egress Pool을 사용하지 않는다.

---

# DataAPI 사용량 전제

약 200개 매장.

평균:

```text
3 platforms/store
4 new reviews/store/day
```

조회:

```text
200 × 3 = 600 calls/day
18,000/month
```

등록:

```text
200 × 4 = 800 calls/day
24,000/month
```

총:

```text
42,000 calls/month
```

---

# 먼저 현재 코드 분석

조사:

```text
DataAPI base URL
all DataAPI endpoints
fetch endpoint
reply/create endpoint
status endpoint
auth
HTTP client
timeouts
retry
scheduler
store identifier
platform account identifier
```

구현 전 보고:

```text
[DATAAPI EGRESS AUDIT]

HTTP client:
DataAPI files:
Fetch flow:
Create flow:
Current retry:
Current timeout:
Stable routing key:
Missing endpoints:
Required changes:
```

---

# Egress Node Config

코드에 IP/credential 하드코딩 금지.

환경변수/config 사용.

Node:

```text
id
host
port
enabled
username(optional)
password(optional)
```

실제 public IP가 아니라 App → Proxy 연결에는 private IP/hostname 사용을 우선한다.

---

# Sticky Routing

매 요청 random 금지.

동일 store/platform account는 정상 상태에서 동일 Egress Node.

Routing key 우선순위:

1. stable platform account ID
2. store ID
3. 기타 영구 identifier

timestamp/requestId 금지.

---

# Rendezvous Hashing

단순:

```text
hash(id) % nodeCount
```

방식 대신 Rendezvous / HRW hashing을 사용한다.

5 → 10 Node 확장 시 기존 mapping 변경을 최소화한다.

---

# DataAPI-only Proxy

다음만 Egress Pool 사용:

```text
DataAPI fetch
DataAPI reply/create
DataAPI status
기타 DataAPI host endpoint
```

다음은 절대 사용하지 않는다:

```text
OpenAI
DB
결제
이메일
사용자 요청
Cloudflare
Yut Review
```

---

# Client Reuse

요청마다 HTTP client/TCP pool을 새로 만들지 않는다.

Node별 reusable client/pool을 검토한다.

예:

```text
Map<EgressNodeId, HttpClient>
```

현재 library에 맞게 구현한다.

---

# Failover

Network-level failure일 때만 failover.

예:

```text
connect timeout
connection refused
TLS transport failure
proxy 502/503
```

다음을 proxy failure로 오인하지 않는다:

```text
400
401
403
404
409
422
```

---

# Circuit Breaker

Node가 반복 실패하면 임시 제외.

```text
ACTIVE
→ failures
→ UNHEALTHY
→ cooldown
→ probe
→ ACTIVE
```

threshold/cooldown config화.

---

# Fail Closed

Production에서 usable Egress Node가 0이면:

```text
App Public IP → DataAPI
```

로 직접 보내면 안 된다.

작업 실패 + retry/alert.

---

# Concurrency

초기:

```text
per-node 2~3
total 10~15
```

config로 관리.

DataAPI 제공사의 rate limit과 실제 측정에 따라 조정.

---

# Scheduling

리뷰 조회 하루 1회.

200 stores × 3 platform 호출을 한 번에 burst하지 않는다.

예:

```text
03:00~03:30 spread
```

등록도 batch 시작은 하루 1회지만
리뷰 1건당 DataAPI 1회 소비되므로 제한 concurrency로 처리.

---

# Idempotency

등록 API가 timeout 후 실제 성공했을 수 있다.

DataAPI가 다음을 지원하는지 코드/docs에서 확인:

```text
idempotency key
external request ID
status query
duplicate guard
```

없다면 ReviewPilot 내부에서 중복 게시 방지 전략을 설계한다.

임의로 DataAPI가 기능을 지원한다고 가정하지 않는다.

---

# Logs

구조화 로그:

```text
requestId
storeId
platform
operation
egressNodeId
latency
attempt
status
failureCategory
```

금지:

```text
API key
password
proxy credential
review full body
reply full body
PII
```

---

# Health

운영자가 Node 상태를 볼 수 있게 내부 admin/metric에 제공.

```text
egress-01 ACTIVE
egress-02 ACTIVE
egress-03 DEGRADED
...
```

public endpoint에 private IP/credential 노출 금지.

---

# Tests

1. 동일 store → 동일 node
2. 다른 store → 분산
3. node 추가 시 mapping 변화 최소
4. disabled node 제외
5. unhealthy node 제외
6. failover
7. 4xx를 proxy 장애로 오인하지 않음
8. network timeout은 장애 판정
9. fetch uses proxy
10. create uses proxy
11. OpenAI direct
12. production zero nodes → fail closed
13. 200 stores 분포 테스트

---

# Docs

생성:

```text
docs/dataapi-egress-architecture.md
```

포함:

- 5 node 구성
- 10 node 확장
- env
- 장애 격리
- node 추가/제거
- rollback
- metrics
- 운영 checklist

---

# 최종 보고

1. 기존 DataAPI flow
2. 적용 endpoint 목록
3. routing key
4. hashing
5. client pool
6. retry/failover
7. circuit breaker
8. fail closed
9. concurrency
10. logs
11. tests
12. config
13. 5→10 확장 방법
