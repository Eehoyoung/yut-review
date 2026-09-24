# Codex Prompt — SodamLabs Backup / DB-Redis Isolation / Operations Hardening

## ROLE

너는 SodamLabs production의 Senior SRE / Security Engineer다.

대상:

```text
Yut Review
ReviewPilot
```

목표:

- DB/Redis public exposure 제거
- production secrets 정리
- Yut Review DB S3 자동 백업
- ReviewPilot Managed DB backup 정책 문서화
- restore procedure 작성
- 운영자가 실제 실행 가능한 scripts/docs 제공

---

# 중요

애플리케이션 기능을 임의로 변경하지 않는다.

먼저 repository의:

```text
Dockerfile
docker-compose*
.env*
.gitignore
DB config
Redis config
backup scripts
migration
deployment docs
```

를 분석한다.

구현 전 보고:

```text
[BACKUP & SECURITY AUDIT]

DB engine:
DB Docker service:
Redis used:
Public ports:
Secret handling:
Existing backup:
Existing restore:
Risks:
Required changes:
```

---

# DB / Redis Isolation

Yut Review production:

DB/Redis를 Docker internal network에서만 사용.

금지:

```yaml
ports:
  - "5432:5432"
```

```yaml
ports:
  - "6379:6379"
```

필요한 경우 localhost bind만 허용:

```text
127.0.0.1
```

ReviewPilot production DB는 Managed PostgreSQL을 사용하고
public access를 비활성화하는 운영안을 문서화한다.

---

# Secrets

Repository에 secret 금지.

점검:

```text
.env
application-prod.yml
docker-compose
shell scripts
CI config
README
```

발견 가능한:

```text
DB password
JWT secret
DataAPI key
OpenAI key
AWS key
proxy password
PG secret
```

를 확인한다.

실제 secret 값을 최종 보고에 그대로 출력하지 않는다.

필요하면 redacted 형태로만 보고한다.

---

# Yut Review Backup

Yut Review DB가 동일 Lightsail에 있을 때:

```text
DB
→ pg_dump 또는 mysqldump
→ gzip
→ S3
```

구조.

프로젝트의 실제 DB engine에 맞춰 script를 작성한다.

파일 예:

```text
scripts/backup-db.sh
scripts/restore-db.sh
```

---

# backup-db.sh 요구사항

- `set -Eeuo pipefail`
- timestamp file name
- dump 성공 여부 검사
- gzip
- empty file 검사
- S3 upload
- upload 실패 시 non-zero exit
- local temporary backup retention
- secret stdout 출력 금지
- log에 password 금지

S3 destination은 env/config로 관리.

예:

```text
BACKUP_S3_BUCKET
BACKUP_S3_PREFIX
BACKUP_LOCAL_RETENTION_DAYS
```

---

# Restore Script

복구 script 또는 명확한 restore runbook을 작성한다.

중요:

Production DB에 즉시 덮어쓰지 않는다.

기본 복원 절차:

```text
S3 download
→ checksum/size 확인
→ temporary/test DB restore
→ 검증
→ production restore 의사결정
```

---

# Backup Schedule

cron 또는 systemd timer 중 현재 운영에 단순한 방식을 선택한다.

예:

```text
04:10 KST daily
```

애플리케이션 batch 시간과 겹치지 않도록 한다.

현재 ReviewPilot/Yut Review schedule을 조사해 충돌 방지.

---

# S3 Security

문서화:

```text
Block Public Access = ON
Encryption = ON
Versioning = 권장
Lifecycle = 설정
Region = ap-northeast-2
```

IAM 최소 권한.

서버에 AdministratorAccess key를 넣지 않는다.

가능하면 backup bucket/prefix 전용 principal/policy.

---

# Lightsail Snapshot

Snapshot은 보조 수단.

명시:

```text
Lightsail snapshot != DB logical backup
```

둘을 동시에 사용.

---

# ReviewPilot

Managed PostgreSQL HA의 기본 backup/restore 특성을 확인하고
운영 문서에 기록한다.

추가 logical dump가 필요하다면:

```text
App-A only
```

또는 별도 lock으로 한 곳에서만 수행.

App-A/App-B가 같은 backup을 중복 생성하지 않게 한다.

---

# Backup Monitoring

backup 성공 여부를 확인 가능한 로그 생성.

최소:

```text
start
finish
duration
file size
S3 key
result
```

secret은 기록하지 않는다.

실패 시 운영자가 알 수 있도록
현재 notification infra가 있다면 연결 가능성을 조사한다.

없는 알림 시스템을 임의 도입하지 않는다.

---

# Restore Test

문서에 최소 월 1회 restore drill 권장.

확인:

```text
backup downloadable
gzip intact
SQL dump valid
test DB restore successful
core tables present
```

---

# Documentation

생성:

```text
docs/backup-and-restore.md
docs/production-security-checklist.md
```

Security checklist:

```text
[ ] DB public port 없음
[ ] Redis public port 없음
[ ] .env gitignored
[ ] secrets repo에 없음
[ ] production debug off
[ ] health endpoint no secrets
[ ] backup daily
[ ] S3 private
[ ] restore tested
```

---

# Validation

Docker config:

```bash
docker compose -f docker-compose.prod.yml config
```

Git:

```bash
git diff
git grep
```

secret-looking patterns를 조사하되
실제 secret 값을 사용자-visible 로그에 출력하지 않는다.

---

# 최종 보고

1. public exposure
2. compose 변경
3. secret 위험
4. backup script
5. restore script
6. S3 env
7. schedule
8. retention
9. ReviewPilot managed backup
10. validation
11. remaining risks
