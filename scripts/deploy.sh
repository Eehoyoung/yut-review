#!/bin/sh
# 운영 서버 자동 배포. 서버 cron이 2분마다 돈다(10_DEPLOYMENT.md "자동 배포").
#
#   sh scripts/deploy.sh            # origin/main 최신 커밋
#   sh scripts/deploy.sh <sha>      # 그 커밋으로 (수동 롤백도 이것)
#
# 끌어오는 방식(pull)이다. GitHub에 SSH 키를 두지 않고 22번을 GitHub 대역에 열지 않는다.
# CI는 테스트를 통과한 커밋에만 sha 태그 이미지를 올리므로, 이미지가 받아지면 곧 배포해도 되는 커밋이다.
# 아직 없으면(빌드 중) 아무것도 하지 않고 다음 차례에 다시 본다.
#
# 순서: DB 백업 → 소스 checkout → 이미지 교체 → health 대기 → 실패 시 직전 커밋·이미지로 되돌림.
# 스키마는 ddl-auto=update라 되돌려지지 않는다. 그래서 백업이 롤백보다 먼저다.

# 본문 전체를 함수로 감싼다. git checkout이 실행 중인 이 파일을 바꿔도 sh가 이미 다 읽은 뒤다.
main() {
  set -eu
  cd "$(dirname "$0")/.."

  # cron이 겹쳐 돌면 두 번째는 그냥 빠진다.
  exec 9>/tmp/yutreview-deploy.lock
  flock -n 9 || exit 0

  COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.production"
  STATE=.deployed-sha

  git fetch -q origin main
  TARGET=$(git rev-parse "${1:-origin/main}")
  CURRENT=$(cat "$STATE" 2>/dev/null || true)
  [ "$TARGET" = "$CURRENT" ] && exit 0
  # 한 번 실패한 커밋은 cron이 다시 잡지 않는다. 다음 커밋이 오거나 인자로 직접 줄 때만 재시도한다.
  [ -z "${1:-}" ] && [ "$TARGET" = "$(cat .failed-sha 2>/dev/null || true)" ] && exit 0

  # shell 변수가 .env.production의 IMAGE_TAG보다 우선한다.
  export IMAGE_TAG="sha-$TARGET"
  if ! $COMPOSE pull -q backend frontend 2>/dev/null; then
    echo "$(date -Is) $TARGET 이미지 아직 없음 — 다음 차례에 다시 본다"
    exit 0
  fi

  echo "$(date -Is) 배포 시작 ${CURRENT:-none} -> $TARGET"

  mkdir -p backups
  DUMP="backups/$(date +%F-%H%M)-$(git rev-parse --short "$TARGET").sql.gz"
  $COMPOSE exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' | gzip > "$DUMP"
  # sh에는 pipefail이 없어 pg_dump 실패가 묻힌다. 덤프 끝 표시로 완주했는지 본다.
  if ! gzip -dc "$DUMP" | tail -n 3 | grep -q 'PostgreSQL database dump complete'; then
    rm -f "$DUMP"; echo "$(date -Is) 백업 실패 — 배포하지 않는다" >&2; exit 1
  fi
  # ponytail: 최근 14개만 남긴다. 장기 보관은 Lightsail 스냅샷이 맡는다.
  ls -t backups/*.sql.gz | tail -n +15 | xargs -r rm -f

  # 로컬 수정이 있으면 덮지 않고 여기서 멈춘다(reset --hard를 쓰지 않는 이유).
  git checkout -q --detach "$TARGET"

  if up_and_wait "$CURRENT" "$TARGET"; then
    echo "$TARGET" > "$STATE"
    docker image prune -f >/dev/null
    echo "$(date -Is) 배포 완료 $TARGET"
    exit 0
  fi

  echo "$(date -Is) health 실패 — 되돌린다" >&2
  [ -n "$CURRENT" ] || { echo "직전 배포 기록이 없어 되돌릴 수 없다. 수동 확인 필요" >&2; exit 1; }
  git checkout -q --detach "$CURRENT"
  export IMAGE_TAG="sha-$CURRENT"
  up_and_wait "$TARGET" "$CURRENT" || echo "롤백도 health 실패. 수동 확인 필요" >&2
  # STATE는 그대로 CURRENT라 다음 cron이 같은 TARGET을 다시 시도한다. 반복을 막으려 표시를 남긴다.
  echo "$TARGET" > .failed-sha
  exit 1
}

# $1→$2로 바꾼 뒤 backend health가 UP이 될 때까지 최대 3분 기다린다.
up_and_wait() {
  $COMPOSE up -d --remove-orphans || return 1
  # nginx 설정은 파일 bind mount라 reload로는 새 inode를 못 본다(CLAUDE.md). 바뀐 때만 재생성한다.
  if [ -z "$1" ] || ! git diff --quiet "$1" "$2" -- nginx; then
    $COMPOSE up -d --no-deps --force-recreate nginx || return 1
  fi
  i=0
  while [ $i -lt 36 ]; do
    curl -fsS --max-time 3 http://127.0.0.1:18080/actuator/health 2>/dev/null | grep -q '"UP"' && return 0
    i=$((i+1)); sleep 5
  done
  return 1
}

main "$@"
