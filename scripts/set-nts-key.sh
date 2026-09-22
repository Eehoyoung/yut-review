#!/bin/sh
# 국세청 진위확인 서비스 키를 .env.production에 넣는다.
#
#   sh scripts/set-nts-key.sh
#
# 키를 인자로 받지 않는다. `sh scripts/set-nts-key.sh <키>`로 두면 그 값이 셸 히스토리와
# `ps` 출력에 그대로 남는다. 대신 화면 입력을 끄고 붙여넣기로 받는다.
#
# 넣어야 하는 것은 공공데이터포털 마이페이지의 **일반 인증키(Decoding)** 원문이다.
# Encoding 쪽을 넣으면 코드가 한 번 더 인코딩해서 이중 인코딩이 되고 국세청이 거부한다.
# (%2B, %2F, %3D 가 보이면 Encoding 키다.)
set -eu

TARGET="${ENV_FILE:-.env.production}"
[ -f "$TARGET" ] || { echo "$TARGET 이 없습니다. 저장소 루트에서 실행하세요." >&2; exit 1; }

printf '공공데이터포털 일반 인증키(Decoding)를 붙여넣고 Enter: '
# 입력 에코를 끈다. 터미널 설정을 바꾸므로 어떻게 끝나든 반드시 되돌린다.
if [ -t 0 ]; then
  saved=$(stty -g)
  trap 'stty "$saved" 2>/dev/null; echo' EXIT INT TERM
  stty -echo
fi
IFS= read -r KEY
if [ -t 0 ]; then stty "$saved"; trap - EXIT INT TERM; fi
echo

[ -n "$KEY" ] || { echo "키가 비어 있습니다." >&2; exit 1; }
case "$KEY" in
  *%2B*|*%2F*|*%3D*|*%2b*|*%2f*|*%3d*)
    echo "이 키는 Encoding 키로 보입니다(%2B/%2F/%3D 포함)." >&2
    echo "마이페이지에서 '일반 인증키(Decoding)' 쪽을 복사해 다시 실행하세요." >&2
    exit 1 ;;
esac
# 구분자로 |를 쓰므로 키에 |가 있으면 sed가 깨진다. 실제 키에는 나오지 않지만 확인은 해 둔다.
case "$KEY" in *"|"*) echo "키에 | 문자가 있어 처리할 수 없습니다." >&2; exit 1 ;; esac

umask 077
set_value() {
  key="$1"; value="$2"; tmp="$TARGET.tmp"
  if grep -q "^${key}=" "$TARGET"; then
    sed "s|^${key}=.*|${key}=${value}|" "$TARGET" > "$tmp"
  else
    cat "$TARGET" > "$tmp"; printf '%s=%s\n' "$key" "$value" >> "$tmp"
  fi
  mv "$tmp" "$TARGET"; chmod 600 "$TARGET"
}

set_value NTS_SERVICE_KEY "$KEY"
set_value BUSINESS_VERIFICATION_ENABLED true

echo "$TARGET 에 넣었습니다. 키 길이 ${#KEY}자 (값은 찍지 않습니다)."
cat <<'NOTE'

재기동하면 적용됩니다.

  docker compose -f docker-compose.yml -f docker-compose.prod.yml \
    --env-file .env.production up -d backend

키가 잘못됐으면 백엔드가 뜨지 않는 것이 아니라, 가입이 전부
BUSINESS_VERIFICATION_UNAVAILABLE(503)로 막힙니다. 재기동 후 반드시 가입을 한 번 시도해
확인하세요. 끄려면 BUSINESS_VERIFICATION_ENABLED=false 로 바꾸고 재기동합니다.
NOTE
