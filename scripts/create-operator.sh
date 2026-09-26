#!/bin/sh
# 첫 시스템 운영자의 이메일만 production env에 등록한다.
# 로그인 비밀번호는 만들지 않는다. 재기동 후 등록 이메일로 OTP를 받아 로그인한다.
set -eu

TARGET="${ENV_FILE:-.env.production}"
EMAIL="${1:-operator@sodamlabs.kr}"

[ -f "$TARGET" ] || { echo "$TARGET 이 없습니다. 저장소 루트에서 실행하세요." >&2; exit 1; }
case "$EMAIL" in
  *@*.*) ;;
  *) echo "올바른 운영자 이메일을 입력하세요." >&2; exit 1 ;;
esac

umask 077
tmp="$TARGET.tmp"
if grep -q '^OPERATOR_BOOTSTRAP_EMAIL=' "$TARGET"; then
  sed "s|^OPERATOR_BOOTSTRAP_EMAIL=.*|OPERATOR_BOOTSTRAP_EMAIL=${EMAIL}|" "$TARGET" > "$tmp"
else
  cp "$TARGET" "$tmp"
  printf '%s=%s\n' OPERATOR_BOOTSTRAP_EMAIL "$EMAIL" >> "$tmp"
fi
mv "$tmp" "$TARGET"
chmod 600 "$TARGET"

cat <<NOTE

  시스템 운영자 이메일: $EMAIL

다음 순서로 마무리합니다.

  1) SMTP_USERNAME과 SMTP_PASSWORD가 설정되어 있는지 확인
  2) 재기동
     docker compose -f docker-compose.yml -f docker-compose.prod.yml \
       --env-file $TARGET up -d
  3) /admin/operator/login 에서 이메일 OTP로 로그인
  4) 첫 운영자 생성 확인 후 OPERATOR_BOOTSTRAP_EMAIL을 비우고 재기동

NOTE
