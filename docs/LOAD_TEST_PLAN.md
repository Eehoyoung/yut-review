# 부하 테스트 계획

작성일: 2026-09-22
대상: 보안점검 finding #1(공개 게임 생성 자원 고갈), #5(공개 가입 자원 고갈)
스크립트: `scripts/load-test/yut-review.js` (k6)
관측: `/admin/operator` 자원 현황 패널 + `GET /api/admin/operator/monitoring`

## 0. 이 테스트가 답해야 하는 질문

숫자를 만드는 것이 목적이 아니다. 네 가지 중 하나라도 대답하지 못하면 테스트를 다시 설계한다.

1. Lightsail 2GB 한 대가 목표 부하를 견디는가.
2. 쿼터가 **경계에서** 닫히는가(30/31이 아니라 정확히).
3. 쿼터가 닫히는 동안 **다른 매장·다른 IP의 정상 손님이 멀쩡한가**. 이것이 finding #1의 진짜 합격 조건이다.
   공격자 한 명 때문에 옆 가게 손님이 못 던지면 방어가 아니라 새로운 장애다.
4. 오래 돌렸을 때 카운터·디스크·메모리가 제자리로 돌아오는가.

## 1. 절대 규칙

- **운영(`hanpan.sodamlabs.kr`)에 대고 돌리지 않는다.** 실제 매장의 일일 상한을 태우고 실제 쿠폰을 발급한다.
  staging 스택(같은 compose, 별도 VM 또는 별도 DB 볼륨)에서만 돌린다.
- 부하 발생기는 **대상 서버 밖**에서 돌린다. 2GB 한 대 위에서 k6를 같이 돌리면 측정하는 것이 애플리케이션이
  아니라 k6가 된다.
- 테스트 데이터에 실제 고객 이름·번호를 쓰지 않는다. 번호는 스크립트가 `010{RUN}{VU}{ITER}`로 만든다.
- 실행이 끝나면 staging DB를 비운다(`docker compose down -v`). 남겨 두면 다음 실행이 2일 쿨타임에 걸린다.

## 2. 목표 규모

현장 테스트 이후 예상되는 1년 차 상한을 기준으로 잡는다.

| 항목 | 목표 |
|---|---|
| 매장 수 | 50곳 |
| 피크 시 동시 참여 손님 | 50명 |
| 한 매장 피크 | 분당 10~15건 (한도 30의 절반 이하) |
| 하루 총 참여 | 5,000건 |
| 가입 신청 | 하루 20건 이하 |

한 매장 분당 30회 한도는 이 표의 두 배 여유다. 실제 회전율이 이 선에 닿는 가게가 생기면 한도가
아니라 목표 규모를 다시 쓴다.

## 3. 준비

### 3.1 staging 스택

```bash
# 운영과 같은 구성으로 띄우되 origin과 DB만 분리한다.
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.staging up -d --build
sh scripts/verify-production.sh https://staging.example
```

### 3.2 매장과 QR 토큰 만들기

가입은 `PENDING_APPROVAL`로 들어가므로 운영자 승인까지 해야 QR이 열린다.
가입 자체도 IP 1시간 5회 한도에 걸리니, 매장을 많이 만들려면 `.env.staging`에 한도를 올리거나
DB에 직접 넣는다.

```bash
BASE=https://staging.example

# 1) 가입 (사업자등록번호는 매장마다 달라야 한다)
curl -sS -X POST "$BASE/api/admin/auth/signup" -H 'Content-Type: application/json' -d '{
  "password":"loadtest1234","passwordConfirm":"loadtest1234",
  "email":"load1@example.test","ownerName":"부하","phone":"01000000001",
  "storeName":"부하매장1","businessNumber":"1000000001",
  "termsAgreed":true,"termsVersion":"<현재 버전>",
  "privacyAgreed":true,"privacyVersion":"<현재 버전>"}'

# 2) 운영자 로그인 → 승인 (SYSTEM_ADMIN 계정 필요)
OP=$(curl -sS -X POST "$BASE/api/admin/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"operator@example.test","password":"..."}' | jq -r .data.accessToken)
curl -sS -X POST "$BASE/api/admin/operator/stores/1/approve" \
  -H "Authorization: Bearer $OP" -H 'Content-Type: application/json' -d '{"note":"부하 테스트"}'

# 3) QR 토큰 꺼내기 (매장 소유자 토큰으로)
curl -sS "$BASE/api/admin/stores/1/qr-codes" -H "Authorization: Bearer $OWNER" | jq -r '.data[0].token'
```

토큰을 모아 `tokens.json`으로 만든다. 시나리오 `quota`는 **최소 2개**가 필요하다
(하나를 밀어붙이는 동안 다른 하나가 멀쩡한지 보기 때문이다).

```json
{ "storeTokens": ["<token-1>", "<token-2>", "<token-3>"] }
```

### 3.3 프로파일 두 개

부하 발생기는 IP 하나다. 기본 한도(IP 분당 10회)를 그대로 두면 **용량을 재기도 전에 429가 난다.**
그래서 목적에 따라 설정을 나눈다.

| 프로파일 | `.env.staging` 설정 | 측정 대상 |
|---|---|---|
| A. 용량 | `TRUSTED_PROXY_CIDRS` 유지, `app.limits.game-per-ip-per-minute=100000`, `-per-store-per-minute=100000` | 순수 처리량·지연·자원 |
| B. 한도 | 기본값 그대로 (30 / 10 / 2000) | 경계 동작과 정상 손님 보호 |

프로파일 A에서 한도를 올리는 것은 **staging에서만**이다. 운영에 그대로 옮기면 finding #1이 되살아난다.

## 4. 시나리오

| # | 이름 | 명령 | 부하 | 확인 |
|---|---|---|---|---|
| S1 | 연기 | `-e SCENARIO=smoke` | VU 2 / 1분 | 전 경로가 200. 배포가 살아 있는지만 본다 |
| S2 | 용량 | `-e SCENARIO=capacity` (프로파일 A) | VU 5→100 / 12분 | p95, p99, 5xx, 컨테이너 자원 |
| S3 | 한도 | `-e SCENARIO=quota` (프로파일 B) | 분당 120건 / 5분 | 429 경계, **다른 매장 손님 정상** |
| S4 | 가입 폭주 | 아래 3.2의 signup 루프 | 1분에 30회 | 5회/1시간에서 429, BCrypt가 서버를 먹지 않는지 |
| S5 | 장시간 | `-e SCENARIO=soak` | VU 10 / 2시간 | `rate_counters` 정리, 메모리, 디스크 |

```bash
k6 run -e BASE=https://staging.example -e TOKENS=./tokens.json -e SCENARIO=capacity \
  scripts/load-test/yut-review.js
```

S4는 k6 없이 충분하다. 한도가 시간 단위라 초당 부하가 의미 없기 때문이다.

```bash
for i in $(seq 1 30); do
  curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$BASE/api/admin/auth/signup" \
    -H 'Content-Type: application/json' -d "{\"password\":\"loadtest1234\",\"passwordConfirm\":\"loadtest1234\",
      \"email\":\"burst$i@example.test\",\"ownerName\":\"부하\",\"phone\":\"01000000$i\",
      \"storeName\":\"폭주$i\",\"businessNumber\":\"200000000$i\",
      \"termsAgreed\":true,\"termsVersion\":\"<버전>\",\"privacyAgreed\":true,\"privacyVersion\":\"<버전>\"}"
done
# 기대: 앞 5건은 200, 나머지는 429 SIGNUP_RATE_LIMITED
```

## 5. 합격 기준

| 지표 | 기준 | 어디서 보나 |
|---|---|---|
| 게임 생성 p95 | < 800ms | k6 `game_create_ms` |
| 게임 생성 p99 | < 2,000ms | k6 |
| 5xx | 0건 | k6 `http_req_failed`, backend 로그 |
| S3에서 밀어붙이지 않은 매장의 429 | **0건** | k6 체크 + 운영자 화면 |
| S3에서 밀어붙인 매장의 429 시작 지점 | 분당 31번째 요청 | `/admin/operator/monitoring` |
| backend 메모리 | 768MB 한도 내, OOM kill 0 | `docker stats` |
| postgres 커넥션 | 풀 고갈 없음 | backend 로그의 Hikari 경고 |
| `rate_counters` 행 수 | 50,000 미만, S5 종료 후 감소 | 운영자 화면 '저장량' |
| 디스크 | S5 동안 증가분이 참여 수에 비례 | `df -h`, 운영자 화면 |

기준 하나라도 넘기면 **한도를 올리기 전에 원인을 먼저 찾는다.** 지연이 문제인데 한도만 올리면
같은 부하에서 더 크게 무너진다.

## 6. 실행 중 볼 것

```bash
# 대상 서버에서
watch -n 5 'docker stats --no-stream; df -h /; free -m'
docker compose logs -f backend | grep -iE 'hikari|OutOfMemory|ERROR'
```

브라우저에서는 `/admin/operator` 자원 현황 패널을 열어 둔다. 1분마다 갱신되며 코드별 차단 수,
카운터 행 수, 오늘 붐비는 매장의 상한 대비 사용률을 보여 준다. S3에서 **밀어붙이지 않은 매장의
사용률이 조용한지**가 핵심 관측점이다.

## 7. 결과 기록

실행마다 `docs/load-test/<날짜>-<시나리오>.md`에 남긴다.

- 커밋 SHA, 프로파일(A/B), `.env.staging`에서 바꾼 한도
- k6 요약 출력(p95/p99/체크 통과율/429 수)
- `docker stats` 피크, 디스크 증가분
- 운영자 화면 스냅샷 수치(차단 코드별 건수, 카운터 행 수)
- 기준 미달 항목과 조치

## 8. 아직 다루지 않는 것

- Cloudflare 앞단의 실제 동작(WAF, 캐시, `limit_req`와의 상호작용). Cloudflare를 지나는 부하는
  무료 플랜에서 차단될 수 있으므로 origin 직결로 측정한다.
- 다중 backend 인스턴스. 지금 구조는 단일 VM 전제이고, rate limit은 DB 카운터라 인스턴스가 늘어도
  같이 세지만 그 상태를 측정한 적은 없다.
- AI 기능 부하. 관리자 전용이고 월 한도가 따로 있어 고객 흐름과 격리돼 있다. 다만 모델 호출이
  트랜잭션 밖에서 도는지는 `SubscriptionAiTest`가 보장한다.
- 3D 시뮬레이션은 전부 브라우저에서 돌아 서버 부하가 아니다. 클라이언트 성능은
  `scripts/measure-customer-flow.mjs`가 따로 본다.
