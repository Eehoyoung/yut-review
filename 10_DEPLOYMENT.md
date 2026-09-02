# DEPLOYMENT

## MVP 구조
```text
Internet
  |
HTTPS
  |
Nginx
  |-------------------|
Next.js            Spring Boot
                       |
                   PostgreSQL

3D Assets -> S3 + CloudFront -> Browser
```

## Docker
개발/초기 운영:
```text
frontend
backend
postgres
nginx
```

운영 안정화 시 PostgreSQL은 RDS 권장.

## 환경변수
Backend:
```text
SPRING_PROFILES_ACTIVE=prod
DB_URL=
DB_USERNAME=
DB_PASSWORD=
JWT_SECRET=
PHONE_HMAC_SECRET=
PHONE_ENCRYPTION_KEY=
```

Frontend:
```text
NEXT_PUBLIC_API_BASE_URL=
NEXT_PUBLIC_ASSET_CDN_URL=
```

## HTTPS
전화번호와 직원 PIN이 전송되므로 운영환경 HTTPS 필수.

## S3 / CDN
저장 대상:
- yut.glb
- texture
- sound
- particle asset

Spring Boot가 대용량 3D asset을 직접 전달하지 않는다.

## Monitoring
MVP 최소:
- Spring Actuator
- Backend error log
- Nginx access log
- DB backup
- Client JS error tracking 권장

## Backup
초기 권장:
```text
DB Daily Backup
Retention 7~14 days
```

## CI/CD
```text
GitHub
  ↓
GitHub Actions
  ↓
Test
  ↓
Docker Build
  ↓
EC2 Deploy
```

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

Quick Tunnel은 테스트/개발 용도이며 URL이 재시작 때 바뀔 수 있다. 정식 운영에서는 AWS/정식 Tunnel/고정 도메인 구성으로 전환한다.

매장 QR에는 absolute Tunnel URL을 DB에 저장하지 않는다. DB에는 `public_token`만 보존하고, 실테스트 QR은 현재 public origin + `/s/{storeToken}`으로 생성한다.

상세 규칙과 현장 체크리스트는 `12_LOCAL_FIELD_TEST.md`를 따른다.
