# DEPLOYMENT

## 운영 목표 구성 (확정)

**AWS Lightsail 2GB 단일 VM + Cloudflare Free 프록시.** 별도 로드밸런서나 RDS를 두지 않는다.
규모가 매장 수십 곳 수준이라 단일 VM으로 충분하고, 컴포넌트가 하나 늘 때마다 운영 부담이 늘기 때문이다.

```text
Browser
  |  HTTPS (Cloudflare Free proxy, SSL mode: Full (strict))
Cloudflare edge
  |  HTTPS (Cloudflare Origin Certificate)
Lightsail 2GB VM : 80/443 만 개방
  |
Nginx (docker)
  |-------------------|
Next.js            Spring Boot
                       |
                   PostgreSQL (같은 VM, docker volume)
```

- postgres / backend / frontend는 host 포트를 publish하지 않는다. 외부 노출은 nginx 80/443뿐이다.
- 3D asset은 프런트 번들에 포함한다. S3/CloudFront는 현재 쓰지 않는다(아래 "S3 / CDN"은 향후 안).
- 트래픽이 이 구성을 넘기면 그때 RDS와 별도 웹 노드를 검토한다. 지금 하면 비용만 는다.

## Docker
```text
postgres
backend
frontend
nginx
cloudflared (profile: field-test 전용, 운영에 절대 섞지 않는다)
```

## 운영 기동

**서버에서 소스를 빌드하지 않는다.** 이미지는 GitHub Actions가 GHCR에 올리고 Lightsail은 받기만 한다.

```bash
cd /opt/yutreview
git pull
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.production pull
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.production up -d
```

`git pull`이 여전히 필요한 것은 nginx 설정과 compose 파일이 이미지가 아니라 bind mount이기 때문이다.
애플리케이션 코드는 이미지에서 온다.

- **`--build`를 붙이지 않는다.** 2GB VM에서 Gradle 빌드는 운영 컨테이너와 메모리를 다투고 대체로 진다.
  `docker-compose.prod.yml`이 `build: !reset null`로 base의 build를 지워 둬서 실수로 붙여도 소스를
  빌드하지는 않는다.
- 이미지는 `ghcr.io/eehoyoung/yut-review-{backend,frontend}`. 소유자나 태그를 바꾸려면
  `.env.production`에 `GHCR_OWNER` / `IMAGE_TAG`를 넣는다. 기본은 `latest`다.
- GHCR 패키지가 private이면 서버에서 먼저 `docker login ghcr.io`를 한 번 해 둔다
  (read:packages 권한의 PAT). public이면 로그인 없이 받는다.
- `docker-compose.prod.yml`이 운영 ingress를 **명시적으로 선택**한다. 이 override 없이 뜨면
  base의 field-test 기본값(HTTP 8088 + `nginx/default.conf`)으로 동작한다.
- `APP_PUBLIC_ORIGIN`과 `TLS_CERT_DIR`는 `:?`로 강제되어 있어 비어 있으면 compose가 뜨지 않는다(fail-closed).
- 이 명령과 `--profile field-test`를 **함께 쓰지 말 것.** Quick Tunnel은 개발/현장테스트 전용이다.
- 환경 변수 시작점은 `.env.production.example`. 실제 `.env.production`과 인증서는 커밋하지 않는다.

### Cloudflare

- DNS: `hanpan.sodamlabs.kr` A 레코드 → Lightsail 고정 IP, **Proxied(주황 구름)**.
- SSL/TLS mode: **Full (strict)**. Flexible은 origin 구간이 평문이라 쓰지 않는다.
- Origin Certificate를 발급해 VM의 `TLS_CERT_DIR`(기본 `/etc/yut-review/certs`)에
  `fullchain.pem` / `privkey.pem` 이름으로 두고 `0600`으로 둔다. 컨테이너에는 `/etc/nginx/certs`로 read-only mount된다.

### Lightsail 방화벽

| 포트 | 정책 |
|---|---|
| 80, 443 | 열기 (가능하면 Cloudflare 대역으로 source 제한) |
| 22 | 관리자 IP로 제한 |
| 5432, 8080, 3000 | **닫기** — docker가 host에 publish하지 않으므로 열 이유가 없다 |

## HTTPS
전화번호와 직원 PIN이 전송되므로 운영환경 HTTPS 필수.
알 수 없는 Host로 오는 요청은 `nginx/production.conf`의 `default_server`가 444로 끊는다.

## Production origin (확정)

- 서비스: Sodam Hanpan
- canonical origin: `https://hanpan.sodamlabs.kr`
- 운영사: 소담랩스
- 대표자: 이호영
- 사업자등록번호: `358-23-02207`

운영에서는 `SPRING_PROFILES_ACTIVE=prod`와 `APP_PUBLIC_ORIGIN=https://hanpan.sodamlabs.kr`를 사용한다.
`application-prod.yml`이 QR/안내물의 절대 URL을 이 origin으로 고정하므로 요청의 Host 헤더를 운영 URL로
신뢰하지 않는다. 브라우저 API와 redirect는 동일 origin 상대경로(`/api`, `/admin/login`)를 유지한다.

운영 Nginx 예시는 `nginx/production.conf`다. 인증서를 `/etc/nginx/certs/fullchain.pem`과
`/etc/nginx/certs/privkey.pem`에 read-only로 제공한 뒤 사용한다. 이 설정은 HTTP를 canonical HTTPS로
308 redirect하고 HSTS 및 기존 CSP/security headers를 적용한다. TLS 종단을 별도 로드밸런서에서 한다면
동일한 redirect/host 규칙을 그 계층에 적용하고 Nginx에는 검증된 `X-Forwarded-Proto/Host`만 전달한다.

현재 인증은 쿠키가 아니라 브라우저 `sessionStorage`의 Bearer JWT다. 따라서 인증 cookie domain을 만들지
않으며 CORS도 열지 않는다. Spring의 production 세션 cookie 기본값만 `Secure`/`SameSite=Strict`로 방어 설정한다.

환경 변수 시작점은 `.env.production.example`이며 실제 비밀값과 인증서는 커밋하지 않는다.

## 클라이언트 IP (real_ip)

rate limit이 "누구인지"를 IP로 센다. Cloudflare 뒤에서는 `$remote_addr`가 edge IP로 고정되어
모든 손님이 한 사람처럼 집계되므로, 두 계층을 **같은 정책으로 맞춰야** 한다.

1. Nginx `nginx/cloudflare-real-ip.conf`: Cloudflare 공식 대역에서 온 연결에 한해 `CF-Connecting-IP`를
   `$remote_addr`로 복원한다. 신뢰 대역 밖에서 보낸 `CF-Connecting-IP`는 무시되므로 위조가 통하지 않는다.
   대역은 바뀌므로 **분기별로 https://www.cloudflare.com/ips 기준으로 갱신**한다.
2. Nginx는 업스트림에 `X-Real-IP`/`X-Forwarded-For`를 자신이 본 `$remote_addr`로 덮어쓰고,
   클라이언트가 보낸 `CF-Connecting-IP`는 지워서 전달하지 않는다.
3. 백엔드 `TRUSTED_PROXY_CIDRS`에는 **그 Nginx의 peer 주소 대역만** 넣는다(같은 docker 네트워크이므로
   기본값 `127.0.0.1/32,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16`으로 충분).
   여기에 Cloudflare 대역을 넣지 말 것 — 백엔드는 Cloudflare와 직접 말하지 않는다.

둘이 어긋나면 결과는 조용한 실패다. 너무 넓으면 `X-Real-IP` 위조가 통하고, 너무 좁으면 socket peer로
떨어져 다시 전역 제한처럼 동작한다. Nginx 설정을 바꿀 때 `TRUSTED_PROXY_CIDRS`도 같이 본다.

앞단 완충으로 `/api/`에 `limit_req`(20r/s, burst 40, 429)를 걸었다. **정확한 한도는 백엔드 DB 카운터가
authoritative**이고 Nginx 쪽은 스프링 스레드에 닿기 전 폭주를 끊는 용도다. 정책 수치를 여기서 읽지 말 것.

## 운영 secret 주입

값을 만드는 곳과 값이 사는 곳을 하나로 둔다. **운영 서버 위에서 직접 만들고, 그 파일에서만 산다.**
로컬에서 만들어 옮기면 그 사이에 클립보드·채팅·터미널 기록이라는 사본이 세 개 생긴다.

```bash
ssh ubuntu@<lightsail-ip>
cd /opt/yutreview
sh scripts/generate-production-secrets.sh        # .env.production 생성, 권한 600
vi .env.production                                # APP_PUBLIC_ORIGIN, TLS_CERT_DIR, LEGAL_* 채우기
```

스크립트가 만드는 값: `JWT_SECRET`, `PHONE_HMAC_SECRET`, `PHONE_ENCRYPTION_KEY`, `DB_PASSWORD`.
`openssl rand -base64`로 만들고 **화면에 찍지 않는다.** 이미 `.env.production`이 있으면 덮어쓰지 않는다
(덮어쓰면 기존 DB의 쿨타임 조회와 복호화가 통째로 깨진다).

지켜야 할 것:

- `.env.production`은 `.gitignore` 대상이다. 커밋하지 않는다.
- 값을 다른 곳(메모, 비밀번호 관리자, 채팅)에 복사하지 않는다. 백업이 필요하면 Lightsail 스냅샷으로 한다.
  이 파일을 잃으면 `PHONE_ENCRYPTION_KEY` 없이는 기존 이름·번호를 복호화할 수 없다.
- 분실 시: DB의 개인정보는 복구 불가다. 새 키로 재시작하면 기존 쿨타임과 미사용 쿠폰 조회가 끊긴다.
  그래서 스냅샷이 실질적인 백업이고, 스냅샷 보관 주기가 곧 키 백업 주기다.
- 키를 바꿔야 할 때는 아래 "PHONE_HMAC_SECRET 회전"을 따른다. 그냥 바꾸지 않는다.

## 배포 후 확인

`docs/security/report.md`에서 "정적 검토라 확인하지 못했다"고 남긴 것들이 여기서 닫힌다.
배포 직후 한 번, 그리고 Cloudflare 설정이나 인증서를 바꿀 때마다 다시 돌린다.

```bash
# Cloudflare를 지나는 경로
sh scripts/verify-production.sh https://hanpan.sodamlabs.kr

# origin 직결까지 (Full strict와 default_server 444는 이 경로에서만 확인된다)
ORIGIN_IP=<lightsail-고정-IP> sh scripts/verify-production.sh https://hanpan.sodamlabs.kr
```

스크립트는 읽기 전용이고 다음을 본다. FAIL이 하나라도 있으면 배포를 공개하지 않는다.

| 확인 | 왜 배포 후에만 가능한가 |
|---|---|
| A 레코드와 `cf-ray` | DNS와 프록시(주황 구름) 상태는 실제 응답에만 나타난다 |
| 인증서 체인 검증, TLS 1.2/1.3 | 파일이 아니라 handshake 결과다 |
| origin 직접 TLS(`ORIGIN_IP`) | Cloudflare SSL mode `Full (strict)`의 전제 조건 |
| HTTP → HTTPS 308 | 실제 리스너가 있어야 한다 |
| HSTS / CSP / frame / mime / referrer / permissions | 응답 헤더 |
| 알 수 없는 Host → 444 | `default_server` fail-closed |
| `/api/actuator/health` UP | 애플리케이션이 Nginx 뒤에서 실제로 도는지 |
| 컨테이너 host 포트 미노출 | 서버에서 돌릴 때만 |

스크립트가 **확인하지 못하는 것**과 그 대신의 근거:

- 백엔드가 위조 `X-Real-IP`를 무시하는지: 밖에서 관측되지 않는다.
  `SecurityRemediationTest.forwardedIpIsTrustedOnlyFromAConfiguredProxy`가 담당한다.
  운영에서는 Nginx access log에 손님 IP가 제대로 찍히는지로 간접 확인한다.
- Lightsail 방화벽: AWS 콘솔에서 직접 본다(80/443만 개방).
- 인증서 만료일과 갱신 담당자: 달력에 남긴다. Cloudflare Origin Certificate는 기본 15년이라
  잊기 쉬운 쪽은 오히려 Cloudflare 계정 접근 권한이다.

배포 순서는 이것이 안전하다. **DNS를 마지막에 바꾼다.**

1. Lightsail 방화벽 80/443 개방
2. 인증서 배치 (`TLS_CERT_DIR`), `.env.production` 생성
3. `... pull && ... up -d` (위 "운영 기동". `--build` 없다)
4. `ORIGIN_IP=<IP> sh scripts/verify-production.sh https://hanpan.sodamlabs.kr` → origin 직결 FAIL 0
5. Cloudflare DNS A 레코드 Proxied로 전환, SSL mode `Full (strict)`
6. `sh scripts/verify-production.sh https://hanpan.sodamlabs.kr` → FAIL 0
7. 운영자 계정으로 `/admin/operator` 자원 현황이 뜨는지 확인

## PHONE_HMAC_SECRET 회전

`PHONE_HMAC_SECRET`은 전화번호 → 해시 매핑의 열쇠다. 그냥 바꾸면 **기존 쿨타임 조회가 전부 깨지고**
(모든 손님이 처음 온 사람이 된다) 이름/번호 복호화도 함께 어긋난다. 반드시 아래 순서로만 바꾼다.

```bash
# 0) 새 키 생성 (값은 어디에도 커밋하지 않는다)
openssl rand -base64 32

# 1) 배포 전 DB 백업 (아래 "롤백" 참고)
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.production \
  exec -T postgres pg_dump -U yut yut_review | gzip > backup-$(date +%F-%H%M).sql.gz
```

2. `.env.production`에서 **기존 키를 `PHONE_HMAC_PREVIOUS_SECRET`으로 옮기고** `PHONE_HMAC_SECRET`에 새 키를 넣는다.
3. 재배포. 이 상태에서 조회는 current/previous 둘 다 보고 쓰기는 항상 current로 한다(서비스 무중단).
4. 운영자(SYSTEM_ADMIN) 계정으로 `POST /api/admin/operator/phone-hash/rehash`를 **1회** 실행한다.
   응답 `{scanned, rehashed}`를 로그로 남긴다.
5. `PHONE_HMAC_PREVIOUS_SECRET=`을 비우고 재배포. 이 시점에 옛 키는 폐기한다.

`PHONE_HMAC_SECRET`은 Base64로 디코딩해 32바이트 이상이어야 하며, 아니면 startup이 실패한다(fail-closed).
키 값은 로그·에러·응답 어디에도 남기지 않는다.

## 저장량 상한과 정리

2GB VM이라 디스크가 조용히 차면 postgres가 먼저 죽는다. 상한을 미리 박아 둔다.

- **docker 로그**: `docker-compose.prod.yml`에서 전 서비스 `json-file` `max-size 10m` × `max-file 3`
  (= 서비스당 최대 30MB). 이 설정은 **새로 만든 컨테이너부터** 적용되므로 override 도입 후 한 번 `up -d`로 재생성한다.
- **메모리**: postgres 512m / backend 768m / frontend 384m / nginx 96m. OOM이 VM 전체를 끌고 가지 않게 한 칸씩 막았다.
- **postgres 볼륨**: 주기적으로 `docker system df -v`로 `postgres-data` 크기를 본다.
  게임/쿠폰 행이 주 증가분이고 매장 일일 생성 상한(2000건)이 상한선 역할을 한다.
- **포스터 PNG**: 매장당 1행이라 매장 수에 선형이다. 그리고 매장 생성이 운영자 승인으로 게이트되므로
  익명 가입으로 이미지가 무한히 늘 수 없다. 별도 정리 잡을 두지 않는 이유다.

## 롤백

배포 전에 반드시 백업부터 받는다. 스키마는 `ddl-auto=update`라 **자동 롤백이 없다**
(컬럼 추가는 되돌려지지 않고, 타입 변경/NOT NULL 제거는 애초에 적용되지 않는다).

```bash
# 1) 배포 전 백업 (필수)
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.production \
  exec -T postgres pg_dump -U yut yut_review | gzip > backup-$(date +%F-%H%M).sql.gz

# 2) 되돌리기 — 이전 이미지 태그로 다시 올린다. 빌드하지 않으므로 수십 초다.
#    태그 목록: https://github.com/Eehoyoung/yut-review/pkgs/container/yut-review-backend
IMAGE_TAG=sha-<이전-커밋-sha>   docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.production up -d
#    nginx 설정이나 compose 자체를 되돌려야 하면 소스도 같이 되돌린다.
#    git checkout <이전-커밋-또는-태그>

# 3) 데이터까지 되돌려야 할 때만 (마지막 수단, 그 사이 데이터는 사라진다)
gunzip -c backup-YYYY-MM-DD-HHMM.sql.gz | docker compose -f docker-compose.yml \
  -f docker-compose.prod.yml --env-file .env.production exec -T postgres psql -U yut -d yut_review
```

- 모든 이미지에 `sha-<커밋>` 태그가 붙어 있다. 롤백 대상이 어느 커밋인지만 알면 된다.
- `down -v`는 운영에서 절대 쓰지 않는다. postgres 볼륨이 지워진다.
- `PHONE_HMAC_SECRET`/`PHONE_ENCRYPTION_KEY`를 바꾼 배포를 롤백할 때는 키도 함께 되돌린다.
  키와 데이터가 어긋나면 복구가 안 된다.

## 아직 검증하지 못한 항목

문서상 확정이지만 실제로 해 보지 않은 것들이다. 첫 운영 배포 때 위 "배포 후 확인"의 7단계를 따르면
아래 항목 대부분이 `scripts/verify-production.sh` 한 번으로 닫힌다. 콘솔에서 눈으로 봐야 하는 것만 남는다.

- [x] Cloudflare DNS 레코드(`hanpan.sodamlabs.kr` A → Lightsail 고정 IP, Proxied) 생성 및 전파 (2026-09-23 확인)
- [x] Cloudflare SSL mode `Full (strict)`, Origin Certificate handshake (2026-09-23, verify-production PASS)
- [x] 실제 TLS 인증서 배치와 권한 0600, 만료 2041-09-17 (2026-09-23)
- [x] 운영 secret 주입 — `generate-production-secrets.sh`로 서버에서 생성, 파일 권한 600 (2026-09-23)
- [x] 국세청 사업자등록 진위확인 실동작 (2026-09-23) — 틀린 대표자명은 `BUSINESS_NOT_VERIFIED`,
      실제 정보는 통과. 백엔드 로그에 키·대표자명·사업자번호 유출 0건.
- [ ] Lightsail 방화벽 규칙(80/443만 개방, 5432/8080/3000 차단) — AWS 콘솔에서 눈으로 확인 필요
- [ ] Cloudflare 경유 시 `X-Real-IP`가 실제 손님 IP로 들어오는지(접근 로그로 확인)
- [ ] 백업 복원 리허설. 스냅샷은 켜 뒀지만 `pg_dump` 복원을 한 번도 해 보지 않았다.
      해 보지 않은 백업은 백업이 아니다.
- [ ] Lightsail 2GB 부하 실측(`docs/LOAD_TEST_PLAN.md`). 유휴 상태 backend 261MiB/768MiB만 쟀다.

## S3 / CDN
저장 대상:
- yut.glb
- texture
- sound
- particle asset

Spring Boot가 대용량 3D asset을 직접 전달하지 않는다.

## Monitoring

자원 고갈 방어는 막는 것까지가 절반이고, 나머지 절반은 무엇을 막고 있는지 보이는 것이다.
운영자(SYSTEM_ADMIN)가 `/admin/operator`의 **자원 현황** 패널에서 본다(1분 갱신).
같은 값을 `GET /api/admin/operator/monitoring`으로도 받는다.

| 값 | 무엇을 말하나 |
|---|---|
| 최근 24시간 코드별 차단 수 | 한도가 정상 손님을 막고 있는지, 공격을 받고 있는지 |
| `rate_counters` 행 수 / 상한 | cardinality. 상한에 닿으면 새 bucket을 거절한다(fail closed) |
| 참여·쿠폰·매장 행 수, 안내물 용량 | 2GB VM에서 디스크가 차는 속도 |
| 오늘 붐비는 매장의 일일 상한 대비 사용률 | 한 매장이 상한에 다가가는지 |

봐야 하는 선:

- 밀어붙이지 않은 매장에서 `GAME_RATE_LIMITED`가 나오면 **한도가 낮은 것**이다.
  `app.limits.game-per-store-per-minute` 등을 올리기 전에 참여 기록의 시간대 분포를 먼저 본다.
- `rate_counters` 사용률 70% 이상이면 purge 주기(10분)나 상한을 손볼 때다.
- 안내물 용량은 매장 수에 비례한다. 매장당 수백 KB를 넘어서면 렌더링 해상도를 다시 본다.

알림 채널은 붙이지 않았다. 매장 수십 곳 규모에서 알림을 먼저 만들면 운영 부담만 늘고, 지금은
운영자가 이 화면을 여는 것이 모니터링이다. 임계값과 부하 시 기대치는 `docs/LOAD_TEST_PLAN.md`에 있다.

그 밖의 최소 구성:
- Spring Actuator (`/api/actuator/health`)
- Backend error log, Nginx access log (손님 IP가 제대로 찍히는지 확인하는 용도이기도 하다)
- DB backup
- Client JS error tracking 권장

## Backup
초기 권장:
```text
DB Daily Backup
Retention 7~14 days
```

## CI/CD

`.github/workflows/publish-images.yml` 하나뿐이다. `main` push와 `v*` 태그, 그리고 수동 실행에서
backend/frontend 이미지를 빌드해 GHCR에 올린다. **서버에 배포하지는 않는다** — Actions에 SSH 키를
주는 것보다 서버에서 `pull && up -d` 두 줄을 치는 편이 낫다고 판단했다. 배포가 잦아지면 그때 바꾼다.

```text
push to main / tag v*
  ↓
GitHub Actions (matrix: backend, frontend)
  ↓
ghcr.io/eehoyoung/yut-review-backend:{latest, sha-<커밋>, v<태그>}
ghcr.io/eehoyoung/yut-review-frontend:{ ... }
  ↓
(수동) Lightsail 에서 pull && up -d
```

- `secrets.GITHUB_TOKEN` + `permissions: packages: write`로 끝난다. 별도 PAT를 만들지 않는다.
- 플랫폼은 `linux/amd64` 고정이다. **Lightsail을 ARM 플랜으로 만들면 컨테이너가 `exec format error`로
  뜨지 않는다.** 그때는 워크플로의 `platforms`를 `linux/arm64`로 바꾼다.
- Gradle/npm 캐시는 `type=gha`에 둔다. 없으면 배포마다 의존성을 전부 다시 받는다.
- 테스트는 아직 이 워크플로에 없다. 로컬에서 `./gradlew test`와 `npm test`를 돌린 뒤 push한다.

Branch 예:
```text
main
develop
feature/*
```


---

# MVP 1차 실배포: 로컬 노트북 현장 테스트

정식 AWS 배포 전에 **로컬 Windows 노트북을 임시 서버로 사용하여 매장에서 즉시 실테스트**한다.

표준 구성:

```text
Cloudflare Quick Tunnel HTTPS
        ↓
Local Laptop
        ↓
Nginx :80
  ├─ /     → frontend:3000
  └─ /api  → backend:8080
                    ↓
               postgres:5432
```

Docker Compose는 최소 다음 서비스를 포함한다.

```text
postgres
backend
frontend
nginx
cloudflared (profile: field-test)
```

Host에서는 Nginx만 `8088:80`으로 노출하는 것을 권장한다.

```bash
docker compose up -d --build
```

로컬 확인:

```text
http://localhost:8088
```

현장 외부망 테스트:

```bash
docker compose --profile field-test up -d --build
docker compose logs -f cloudflared
```

또는 Windows에 cloudflared를 설치한 경우:

```bash
cloudflared tunnel --url http://localhost:8088
```

Cloudflare가 제공하는 임시 `https://*.trycloudflare.com` 주소로 LTE/5G 휴대폰에서 접근한다.

Quick Tunnel은 **개발/현장테스트 전용**이며 URL이 재시작 때 바뀔 수 있다. 정식 운영은 위
"운영 목표 구성"(Lightsail + Cloudflare 프록시 + Origin Certificate)이다.

`--profile field-test`와 `docker-compose.prod.yml`을 **함께 쓰지 말 것.** cloudflared를 운영 ingress에
붙이면 nginx가 보는 peer가 cloudflared 컨테이너 IP가 되어 real_ip 복원이 무력화되고, TLS 종단도 이중이 된다.
그래서 `docker-compose.prod.yml`에는 cloudflared 서비스를 아예 넣지 않았다.

매장 QR에는 absolute Tunnel URL을 DB에 저장하지 않는다. DB에는 `public_token`만 보존하고, 실테스트 QR은 현재 public origin + `/s/{storeToken}`으로 생성한다.

상세 규칙과 현장 체크리스트는 `12_LOCAL_FIELD_TEST.md`를 따른다.
