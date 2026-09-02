# LOCAL FIELD TEST DEPLOYMENT

## 1. 목적

MVP 구현 직후 별도의 클라우드 서버 없이 **개발자 Windows 노트북을 임시 실테스트 서버**로 사용한다.

애플리케이션은 Docker Compose로 운영형에 가깝게 구동하고, 외부 고객 휴대폰은 **Cloudflare Quick Tunnel (TryCloudflare)** 을 통해 HTTPS로 접속한다.

이 구성은 실제 매장 단기 실테스트/데모용이며 정식 운영 배포용이 아니다.

---

## 2. 실테스트 표준 구조

```text
Customer Phone (LTE/5G/다른 Wi-Fi)
          |
          | HTTPS
          v
https://<random>.trycloudflare.com
          |
          v
Cloudflare Quick Tunnel
          |
          v
Local Laptop
          |
          v
Nginx Docker :80
    |               |
    | /             | /api
    v               v
Next.js :3000   Spring Boot :8080
                        |
                        v
                PostgreSQL :5432
```

### 외부에 공개하는 것은 Nginx 하나뿐이다.

- Frontend 직접 외부 노출 금지
- Backend 직접 외부 노출 금지
- PostgreSQL 외부 노출 금지
- 외부 요청은 모두 Nginx를 통해 들어온다.

이 구조를 사용하면 Frontend와 API가 동일 Origin을 사용하므로 랜덤 Tunnel hostname에서도 CORS 구성이 단순해진다.

---

## 3. Docker Compose 실테스트 프로필

Codex 구현 결과물은 최소 아래 서비스를 제공해야 한다.

```text
postgres
backend
frontend
nginx
cloudflared (field-test profile)
```

권장 포트:

```text
Host 8088 -> nginx:80
```

Backend/Frontend/PostgreSQL은 Docker network에서만 서로 통신하고, host에 직접 publish하지 않는다.

로컬 브라우저 확인 주소:

```text
http://localhost:8088
```

---

## 4. 실행 방법

### 일반 로컬 운영형 실행

```bash
docker compose up -d --build
```

확인:

```text
http://localhost:8088
```

### 매장 외부망 실테스트

권장 방식은 Docker Compose의 `field-test` profile이다.

```bash
docker compose --profile field-test up -d --build
```

Cloudflare Quick Tunnel 주소 확인:

```bash
docker compose logs -f cloudflared
```

로그에 다음 형태의 URL이 표시된다.

```text
https://<random>.trycloudflare.com
```

이 URL은 고객의 LTE/5G 또는 다른 Wi-Fi에서도 접속 가능해야 한다.

### cloudflared를 Windows host에서 직접 실행하는 대안

Docker 앱을 먼저 구동한 후:

```bash
cloudflared tunnel --url http://localhost:8088
```

생성된 `https://*.trycloudflare.com` 주소를 사용한다.

---

## 5. Cloudflare Quick Tunnel 특성

- 무료 테스트 용도
- Cloudflare 계정이나 소유 도메인 없이 사용 가능
- `trycloudflare.com` 임시 HTTPS URL 자동 생성
- URL은 tunnel process를 새로 시작하면 변경될 수 있음
- 노트북이 꺼지거나 절전하거나 tunnel process가 종료되면 외부 접속도 종료됨
- 정식 production 용도로 사용하지 않음

반복적인 장기 매장 테스트에서 고정 주소가 필요해지는 시점에는 Cloudflare Named Tunnel + 보유 도메인 또는 정식 서버 배포로 전환한다.

---

## 6. 매장 QR 생성 규칙

### DB에 저장

```text
store.publicToken
```

또는 QR 테이블의:

```text
store_qr_codes.public_token
```

### DB에 저장하지 말 것

```text
https://abc.trycloudflare.com/s/...
```

Quick Tunnel hostname은 임시 주소이기 때문이다.

실테스트 QR URL:

```text
{CURRENT_PUBLIC_ORIGIN}/s/{storeToken}
```

예:

```text
https://random.trycloudflare.com/s/qR7GdJ42mKX1
```

관리자 페이지가 Tunnel URL로 열려 있다면 **현재 브라우저 origin을 사용하여 QR을 생성/다운로드**할 수 있도록 구현하는 것을 권장한다.

### 중요

Quick Tunnel을 재시작해 hostname이 바뀌면 기존에 출력한 테스트 QR은 더 이상 유효한 주소를 가리키지 않는다.

따라서 매장 실테스트 시작 순서:

```text
Docker 실행
-> Tunnel URL 확인
-> Tunnel URL로 관리자 접속
-> 매장 QR 생성/다운로드
-> 현장 출력 또는 태블릿/화면으로 제공
-> 고객 실테스트 시작
```

---

## 7. Nginx Routing

권장 개념:

```nginx
/       -> frontend:3000
/api/   -> backend:8080
```

Backend API는 frontend와 동일 hostname에서 접근한다.

Frontend production 환경에서는 가능하면:

```text
/api
```

상대경로 API base를 사용하여 Quick Tunnel hostname 변경에 영향을 받지 않도록 한다.

---

## 8. Field-test 환경 변수

권장 파일:

```text
.env.field-test
```

예시 범주:

```text
SPRING_PROFILES_ACTIVE=field-test
DB_NAME=
DB_USERNAME=
DB_PASSWORD=
JWT_SECRET=
PHONE_HMAC_SECRET=
PHONE_ENCRYPTION_KEY=
TZ=Asia/Seoul
```

실제 secret 값은 Git에 commit하지 않는다.

`.env.field-test.example`만 repository에 포함한다.

---

## 9. 외부 SaaS 의존성 없는 상태로 구동

현장 MVP 테스트에서는 아래 서비스가 없어도 전체 핵심 Flow가 동작해야 한다.

```text
AWS EC2 - 불필요
RDS - 불필요
S3/CloudFront - 불필요
Redis - 불필요
Solapi - 불필요
SMS - 불필요
```

3D 윷 asset은 field-test 단계에서는 frontend static/public asset 또는 Docker image에 포함할 수 있다.

향후 운영 배포에서 S3/CloudFront boundary로 교체한다.

---

## 10. 현장 테스트 전 Health Check

다음 조건을 모두 통과한 후 QR을 고객에게 제공한다.

### Docker

```bash
docker compose ps
```

모든 핵심 container가 `Up` 또는 `healthy` 상태여야 한다.

### Local

```text
http://localhost:8088
```

정상 렌더링.

### Backend Health

Nginx를 통한 health endpoint를 제공한다.

예:

```text
http://localhost:8088/api/actuator/health
```

### External

Cloudflare public URL을 **노트북과 다른 네트워크의 휴대폰**에서 직접 확인한다.

확인 항목:

- Landing 로드
- 네이버 플레이스 이동
- 이름/전화번호 입력
- 직원 PIN 승인
- 3D 윷 실행
- 상품 발급
- 쿠폰 조회
- 직원 PIN 사용처리
- 2일 쿨타임

---

## 11. 노트북 운영 주의사항

현장 테스트 중:

- 전원 어댑터 연결
- Windows 자동 절전/최대 절전 비활성화
- Docker Desktop 실행 유지
- 인터넷 연결 유지
- cloudflared container/process 종료 금지
- Windows 재부팅/로그아웃 주의
- 운영 중 Docker compose down 금지

노트북 또는 tunnel이 내려가면 고객 접속도 즉시 중단된다.

---

## 12. 보안

Quick Tunnel로 공개하는 origin은 Nginx 하나로 제한한다.

특히 다음 포트를 인터넷에 직접 노출하지 않는다.

```text
5432 PostgreSQL
8080 Spring Boot
3000 Next.js
```

Router Port Forwarding은 필요하지 않다.

현장 테스트라 하더라도:

- 기본 admin password 금지
- 실제 secret 사용
- 직원 PIN 로그 금지
- 전화번호 평문 로그 금지
- `.env.field-test` Git commit 금지

규칙을 유지한다.

---

## 13. 현장 실테스트 완료 조건

- [ ] Docker Compose 한 명령으로 전체 핵심 stack 실행
- [ ] Nginx 단일 ingress
- [ ] localhost:8088 접속 성공
- [ ] Cloudflare Quick Tunnel HTTPS 접속 성공
- [ ] 외부 LTE/5G 휴대폰 접속 성공
- [ ] 현재 Tunnel origin 기반 매장 QR 생성 성공
- [ ] QR 스캔 후 올바른 Store 식별
- [ ] 직원 PIN 승인 성공/실패 검증
- [ ] 서버 확정 결과와 3D 윷 결과 일치
- [ ] Coupon 발급/사용 성공
- [ ] 동일 Coupon 2회 사용 불가
- [ ] 동일 매장 동일 번호 D+1 재참여 차단
- [ ] D+2 재참여 허용
- [ ] Tunnel 재시작 시 테스트 QR 재생성 절차 문서화
