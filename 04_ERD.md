# ERD / DATABASE DESIGN

## ERD
```mermaid
erDiagram
    ADMIN_USERS ||--o{ ADMIN_STORE_MEMBERSHIPS : manages
    STORES ||--o{ ADMIN_STORE_MEMBERSHIPS : managed_by
    STORES ||--o{ STORE_QR_CODES : has
    STORES ||--|| STORE_POSTERS : has
    STORES ||--o{ PRIZES : has
    STORES ||--o{ STORE_OUTCOMES : has
    STORES ||--o{ GAME_PLAYS : has
    STORES ||--o{ COUPONS : has
    GAME_PLAYS ||--|| COUPONS : issues
    PRIZES ||--o{ COUPONS : snapshot_from
    PRIZES ||--o{ STORE_OUTCOMES : awarded_by
```

## `admin_users`
| Column | Type | Note |
|---|---|---|
| id | BIGINT | PK |
| email | VARCHAR(255) | UNIQUE |
| password_hash | VARCHAR(255) | |
| name | VARCHAR(100) | 대표자 이름 |
| phone | VARCHAR(30) | 대표 연락처 |
| role | VARCHAR(30) | SYSTEM_ADMIN / STORE_ADMIN |
| created_at | DATETIME | |

## `stores`
| Column | Type | Note |
|---|---|---|
| id | BIGINT | PK |
| name | VARCHAR(100) | 매장명 |
| business_number | VARCHAR(30) | nullable |
| phone | VARCHAR(30) | |
| address | VARCHAR(255) | |
| naver_place_url | VARCHAR(500) | |
| staff_pin_hash | VARCHAR(255) | 6자리 PIN hash |
| status | VARCHAR(20) | ACTIVE / INACTIVE |
| created_at | DATETIME | |
| updated_at | DATETIME | |

## `admin_store_memberships`
| Column | Type | Note |
|---|---|---|
| id | BIGINT | PK |
| admin_user_id | BIGINT | FK |
| store_id | BIGINT | FK |
| role | VARCHAR(30) | OWNER / MANAGER |
| created_at | DATETIME | |

제약:
```text
UNIQUE(admin_user_id, store_id)
```

## `store_qr_codes`
| Column | Type | Note |
|---|---|---|
| id | BIGINT | PK |
| store_id | BIGINT | FK |
| public_token | VARCHAR(100) | UNIQUE |
| status | VARCHAR(20) | ACTIVE / REVOKED |
| created_at | DATETIME | |
| revoked_at | DATETIME | nullable |

## `store_posters`
매장 생성 시 현재 공개 origin, 매장명, 활성 QR로 자동 생성되는 A6 비율 PNG 안내물이다.

| Column | Type | Note |
|---|---|---|
| id | BIGINT | PK |
| store_id | BIGINT | FK, UNIQUE |
| content_base64 | TEXT | PNG 원본의 Base64, 서버 저장 |
| public_origin | VARCHAR(500) | 생성 당시 공개 origin |
| created_at | DATETIME | |
| updated_at | DATETIME | |

Quick Tunnel 주소, 매장명 또는 QR 토큰이 바뀌면 같은 행을 새 PNG로 갱신한다.

## `prizes`
| Column | Type | Note |
|---|---|---|
| id | BIGINT | PK |
| store_id | BIGINT | FK |
| rank | INT | 1이 1등, 매장별 1~5 |
| name | VARCHAR(100) | |
| description | VARCHAR(500) | |
| redeem_policy | VARCHAR(30) | SAME_DAY/NEXT_DAY/ANYTIME |
| active | BOOLEAN | |
| created_at | DATETIME | |
| updated_at | DATETIME | |

제약:
```text
UNIQUE(store_id, rank)
```

## `store_outcomes`
매장별 윷 결과 가중치와 상품 슬롯 매핑. 매장당 정확히 5행.

| Column | Type | Note |
|---|---|---|
| id | BIGINT | PK |
| store_id | BIGINT | FK |
| yut_result | VARCHAR(20) | DO/GAE/GEOL/YUT/MO |
| weight | INT | 0~1000, 확률 = weight / sum(weight) |
| prize_rank | INT | 이 결과가 주는 상품 등급 |
| updated_at | DATETIME | |

제약:
```text
UNIQUE(store_id, yut_result)
```

사용 중인 `prize_rank`의 종류 수가 곧 그 매장의 등급 수다.
가중치는 등급이 아니라 결과에 붙는다. 화면에 실제로 떨어지는 것은
도개걸윷모 다섯 가지이고, 등급에 확률을 걸면 던져진 모양과 상품이
어긋날 수 있기 때문이다.

기본값(신규 매장):

| yut_result | weight | prize_rank |
|---|---|---|
| DO | 325 | 3 |
| GAE | 325 | 3 |
| GEOL | 125 | 2 |
| YUT | 125 | 2 |
| MO | 100 | 1 |

## `game_plays`
| Column | Type | Note |
|---|---|---|
| id | BIGINT | PK |
| public_id | CHAR(26) | ULID 권장 |
| store_id | BIGINT | FK |
| qr_code_id | BIGINT | FK |
| customer_name_encrypted | TEXT | AES-256-GCM |
| phone_hash | CHAR(64) | HMAC-SHA256 |
| phone_encrypted | TEXT | AES-GCM |
| phone_last4 | CHAR(4) | |
| yut_result | VARCHAR(20) | DO/GAE/GEOL/YUT/MO |
| prize_rank | INT | 발급 당시 상품 등급 |
| status | VARCHAR(20) | CREATED/REVEALED/CANCELLED |
| animation_seed | VARCHAR(100) | |
| idempotency_key | VARCHAR(100) | UNIQUE |
| played_date | DATE | Asia/Seoul |
| played_at | DATETIME | |
| revealed_at | DATETIME | nullable |

인덱스:
```text
INDEX(store_id, phone_hash, played_date)
INDEX(store_id, phone_hash, played_at)
INDEX(store_id, played_at)
```

## `coupons`
| Column | Type | Note |
|---|---|---|
| id | BIGINT | PK |
| store_id | BIGINT | FK |
| game_play_id | BIGINT | FK, UNIQUE |
| prize_id | BIGINT | FK |
| coupon_token | VARCHAR(100) | UNIQUE |
| phone_hash | CHAR(64) | 조회 최적화 |
| prize_name_snapshot | VARCHAR(100) | |
| prize_description_snapshot | VARCHAR(500) | |
| redeem_policy_snapshot | VARCHAR(30) | |
| prize_rank_snapshot | INT | 발급 시점 등급 동결 |
| status | VARCHAR(20) | ISSUED/REDEEMED/EXPIRED/CANCELLED |
| valid_from | DATETIME | |
| expires_at | DATETIME | |
| issued_at | DATETIME | |
| redeemed_at | DATETIME | nullable |

인덱스:
```text
INDEX(store_id, phone_hash, status)
INDEX(store_id, status)
INDEX(store_id, expires_at)
INDEX(store_id, issued_at)
```

## 전화번호 저장 규칙
입력:
```text
010-1234-5678
```
정규화:
```text
01012345678
```
저장:
```text
phone_hash      = HMAC-SHA256(normalizedPhone)
phone_encrypted = AES-GCM(normalizedPhone)
phone_last4     = 5678
```

## 쿨타임 계산
최근 `played_date`를 조회하고:
```text
nextPlayableDate = lastPlayedDate + 2 days
```
오늘이 `nextPlayableDate` 이상이면 참여 가능.

## 미사용 쿠폰 우선조회
조건:
```text
store_id = 현재 매장
phone_hash = 현재 고객
status = ISSUED
```
가장 최근 쿠폰 1개를 우선 반환한다.
