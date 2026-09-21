#!/bin/sh
# 운영 secret 생성·주입.
#
# 반드시 운영 서버(Lightsail) 위에서 직접 실행한다. 생성된 값은 화면에도 로그에도 찍지 않고
# .env.production 파일에만 들어간다. 값을 사람이 눈으로 보는 순간 그 값은 채팅·메모·스크린샷으로
# 새어 나갈 경로가 하나 생긴다.
#
#   ssh ubuntu@<lightsail-ip>
#   cd /opt/yut-review
#   sh scripts/generate-production-secrets.sh
#   vi .env.production   # DB_PASSWORD 외 사업자 정보 등 나머지 항목을 채운다
#
# 이미 .env.production이 있으면 덮어쓰지 않는다. 키를 덮어쓰면 기존 DB의 쿨타임 조회와
# 이름·번호 복호화가 통째로 깨진다. 키 회전은 10_DEPLOYMENT.md의 절차를 따른다.
set -eu

TARGET="${1:-.env.production}"
EXAMPLE=".env.production.example"

if [ -f "$TARGET" ]; then
  echo "이미 $TARGET 이 있습니다. 덮어쓰지 않습니다." >&2
  echo "키를 바꾸려면 10_DEPLOYMENT.md의 'PHONE_HMAC_SECRET 회전' 절차를 따르세요." >&2
  exit 1
fi
if [ ! -f "$EXAMPLE" ]; then
  echo "$EXAMPLE 을 찾을 수 없습니다. 저장소 루트에서 실행하세요." >&2
  exit 1
fi
command -v openssl >/dev/null 2>&1 || { echo "openssl이 필요합니다." >&2; exit 1; }

# umask를 먼저 좁힌다. 파일을 만든 뒤에 chmod 하면 그 사이에 읽을 수 있는 창이 열린다.
umask 077
cp "$EXAMPLE" "$TARGET"

# JWT_SECRET은 길이(32자 이상)만 보고, 나머지 둘은 Base64 32바이트여야 한다.
# -A: 줄바꿈 없이. 줄바꿈이 섞이면 .env 파서가 값을 잘라 먹는다.
set_value() {
  key="$1"; value="$2"
  # 값에 / 가 들어가므로 구분자를 |로 둔다. Base64에 |는 나오지 않는다.
  tmp="$TARGET.tmp"
  sed "s|^${key}=.*|${key}=${value}|" "$TARGET" > "$tmp"
  mv "$tmp" "$TARGET"
}

set_value JWT_SECRET "$(openssl rand -base64 48 | tr -d '\n')"
set_value PHONE_HMAC_SECRET "$(openssl rand -base64 32 | tr -d '\n')"
set_value PHONE_ENCRYPTION_KEY "$(openssl rand -base64 32 | tr -d '\n')"
set_value DB_PASSWORD "$(openssl rand -base64 24 | tr -d '\n')"

chmod 600 "$TARGET"

echo "$TARGET 을 만들었습니다(권한 600)."
cat <<'NOTE'

생성한 값: JWT_SECRET, PHONE_HMAC_SECRET, PHONE_ENCRYPTION_KEY, DB_PASSWORD
아직 채워야 하는 값: APP_PUBLIC_ORIGIN, TLS_CERT_DIR, LEGAL_* (사업자 정보)

지켜야 할 것
  - 이 파일을 커밋하지 않는다(.gitignore 대상).
  - 값을 화면에 찍거나 복사해서 다른 곳에 옮기지 않는다. 백업이 필요하면 서버 스냅샷으로 한다.
  - PHONE_HMAC_SECRET / PHONE_ENCRYPTION_KEY를 바꾸면 기존 DB의 쿨타임 조회와
    이름·번호 복호화가 깨진다. 회전 절차는 10_DEPLOYMENT.md에 있다.
NOTE
