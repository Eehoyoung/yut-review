# 50개 매장 성능·비용 측정 결과

## 결론

- 관리자 참여·쿠폰 목록은 DB `LIMIT/OFFSET` 기반 페이지 조회로 변경했다. 기본 50건, 최대 100건이다.
- 개인정보는 KST 기준 120일째까지 보존하고 121일차부터 매일 03:15에 1,000건씩 익명화한다. 유효한 `ISSUED` 쿠폰은 만료 전까지 제외한다.
- 고객 1회 완주 cold-cache 실측은 46 requests, API 6회, 1.534 MB 전송이었다. 같은 쿠폰 warm 재접속은 4.4 KB였다.
- 50개 매장 base(매장당 하루 60회)는 월 90,000게임, API 540,000회, DB 약 86 MB 증가, outbound 약 41.7~138.1 GB 범위다.
- Spring Boot idle RSS가 434.9 MiB여서 backend 512 MB는 위험하다. 시작점은 backend 1 GB, frontend 512 MB, PostgreSQL 5 GB 이상을 권한다.

## Pagination

변경 전에는 매장의 모든 행을 정렬 조회하고, 참여 내역은 이름을 전부 복호화한 뒤 전체 JSON을 만들었다. 100,000행 PostgreSQL 쿼리만 각각 20.043 ms(game plays), 16.690 ms(coupons)였고 이후 JVM 메모리·복호화·직렬화 비용이 추가되는 구조였다.

변경 후에는 두 API 모두 50행만 반환하며 `(store_id, played_at)` 및 새 `(store_id, issued_at)` 인덱스를 역방향 스캔한다. 100,000행에서 DB 실행은 0.133 ms와 0.125 ms였다.

| 데이터 수 | 참여 50행 payload | 참여 평균 | 쿠폰 50행 payload | 쿠폰 평균 |
|---:|---:|---:|---:|---:|
| 10,000 | 8,099 B | 42.64 ms | 7,901 B | 47.96 ms |
| 50,000 | 8,100 B | 46.38 ms | 7,902 B | 56.04 ms |
| 100,000 | 8,101 B | 29.54 ms | 7,903 B | 44.05 ms |

HTTP 수치는 localhost에서 각 5회 평균이며 Page의 count query, 인증, 복호화, JSON 직렬화를 포함한다.

## 개인정보 익명화

익명화 대상은 `playedDate < KST 오늘 - 120일`이다. 따라서 119일·정확히 120일 데이터는 유지되고 121일 데이터부터 처리된다.

- 파기: 이름 암호문, 전화번호 암호문, game/coupon 전화 HMAC, 끝 4자리
- 유지: 매장, 결과, 상품 등급, 참여일, 상태, 쿠폰 상품 snapshot·시각·token 등 비식별 통계/기능 필드
- 예외: `ISSUED`이면서 현재 만료되지 않은 쿠폰
- 안전성: 한 트랜잭션에 최대 1,000개 ID만 읽고 sentinel로 덮어써 재실행 결과가 같다.

## Production bundle

| Route | raw | gzip | brotli | shared raw | route-only raw |
|---|---:|---:|---:|---:|---:|
| `/s/[storeToken]` | 388,783 B | 117,533 B | 99,639 B | 381,126 B | 7,657 B |
| `/identify` | 370,807 B | 110,543 B | 93,267 B | 350,756 B | 20,051 B |
| `/game` | 3,312,904 B | 1,104,894 B | 854,078 B | 350,756 B | 2,962,148 B |
| `/result/[playId]` | 387,228 B | 116,767 B | 98,979 B | 381,126 B | 6,102 B |
| `/coupon/[couponToken]` | 385,481 B | 115,596 B | 97,921 B | 372,589 B | 12,892 B |

전체 route unique asset은 raw 3.390 MB, gzip 1.135 MB, brotli 0.880 MB다. Rapier는 별도 WASM 요청이 아니라 2.100 MB raw / 0.771 MB gzip JS chunk에 포함된다. Three.js 주요 두 chunk는 raw 367 KB와 331 KB이며 R3F/Three 식별 chunk는 raw 41 KB다. Drei는 minified marker로 독립 귀속하지 못했다.

실제 cold 흐름은 JS 1.155 MB, CSS 19.7 KB, Pretendard 12 subsets 340.4 KB, API JSON 4.0 KB를 전송했다. warm 쿠폰 재접속에서는 JS/CSS/font 전송이 0이었다.

## 고객 전체 흐름

실제 production Docker + headless Chrome CDP 결과:

`store summary → customer-state → game create → reveal → result reveal → coupon GET`

- 총 46 requests, backend API 6회
- transferred 1,534,318 B, decoded body 3,825,224 B
- reveal 2회가 관측됐고 같은 결과/쿠폰을 반환하는 멱등 경로였다. 두 번째 호출은 756 B를 추가 전송한다.
- warm 쿠폰 재접속은 24 requests, transferred 4,417 B였다.
- `favicon.ico` 404 하나 때문에 자동 결과 상태는 partial이지만 고객 완주 자체는 성공했다.

## 50개 매장 월간 모델

30일, 실제 cold 1,534,318 B, warm coupon reconnect 4,417 B, 100,000행 실측 table+index 960.5 B/game+coupon을 사용했다.

| 시나리오 | 월 게임/API | 신규 rows (각 table) | DB 증가 | outbound cold 100% | cold 70% | cold 30% |
|---|---:|---:|---:|---:|---:|---:|
| low 30회/일 | 45,000 / 270,000 | 45,000 | 43.2 MB | 69.0 GB | 48.4 GB | 20.9 GB |
| base 60회/일 | 90,000 / 540,000 | 90,000 | 86.4 MB | 138.1 GB | 96.8 GB | 41.7 GB |
| high 100회/일 | 150,000 / 900,000 | 150,000 | 144.1 MB | 230.1 GB | 161.3 GB | 69.5 GB |

관리자가 매장당 매일 참여 1페이지와 쿠폰 1페이지를 읽는다고 가정하면 두 API JSON은 약 0.024 GB/월이다. 관리자 정적 asset은 cache 조건을 별도 측정하지 않아 포함하지 않았다.

## Render에 대입할 사용량

- Web Service: frontend + backend 2개. `/api` 동일 origin을 플랫폼 route로 만들 수 없으면 소형 reverse proxy가 추가로 필요하다.
- Frontend: 512 MB 시작점(실측 idle 54.88 MiB)
- Backend: 1 GB 시작점(실측 idle 434.9 MiB). 512 MB 선택 전에는 메모리 제한 load test가 필수다.
- PostgreSQL: 최소 5 GB와 growth alert. 개인정보 익명화 후에도 통계 행은 유지돼 저장량은 계속 증가한다.
- Outbound: 위 표의 20.9~230.1 GB/월 범위를 현재 Render 요금표에 대입한다.
- Build: 로컬 Docker 실측 frontend 46.7초, backend 31.5초였다. Render cache 유무에 따라 달라지므로 가격은 하드코딩하지 않았다.

## 남은 위험과 경계

- Analytics summary는 호출당 count query 8개다.
- 게임 생성은 매장 단위 pessimistic write lock이라 같은 매장의 동시 참여를 직렬화한다.
- PostgreSQL Base64 poster는 실제 정상 PNG 크기를 이번 bootstrap 데이터에서 신뢰성 있게 얻지 못했다.
- 이 수치는 localhost production Docker 결과다. Render edge, Cloudflare, LTE/5G, 실기기 브라우저는 아직 측정하지 않았다.

기계 판독용 전체 값은 `docs/50stores-performance-report.json`에 있다.
