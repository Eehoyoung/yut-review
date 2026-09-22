#!/bin/sh
# 첫 운영자 계정을 무작위 비밀번호로 만든다.
#
#   sh scripts/create-operator.sh                       # operator@sodamlabs.kr
#   sh scripts/create-operator.sh admin@example.com     # 이메일 지정
#
# scripts/generate-production-secrets.sh와 달리 **비밀번호를 화면에 한 번 찍는다.**
# 저쪽 값들은 사람이 볼 일이 없어서 감추는 것이 이득이지만, 이 값은 사람이 로그인에 써야 한다.
# 감추면 쓸 수가 없다. 대신 노출 시간을 최대한 줄인다 — 한 번만 찍고, 비밀번호 관리자에 넣은
# 뒤 두 줄을 지우라고 안내한다.
#
# 운영자가 이미 한 명이라도 있으면 백엔드가 이 값을 무시한다. 그래서 이 스크립트로는
# 비밀번호를 바꿀 수 없다. 잊었으면 DB에서 그 계정의 role을 STORE_ADMIN으로 내린 뒤 다시 돌린다.
set -eu

TARGET="${ENV_FILE:-.env.production}"
EMAIL="${1:-operator@sodamlabs.kr}"

[ -f "$TARGET" ] || { echo "$TARGET 이 없습니다. 저장소 루트에서 실행하세요." >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "openssl이 필요합니다." >&2; exit 1; }

if grep -q '^OPERATOR_BOOTSTRAP_PASSWORD=.\+' "$TARGET"; then
  echo "$TARGET 에 OPERATOR_BOOTSTRAP_PASSWORD가 이미 채워져 있습니다." >&2
  echo "재기동이 아직이면 그대로 재기동하세요. 끝났으면 그 두 줄을 지운 뒤 다시 실행하세요." >&2
  exit 1
fi

# 백엔드 규칙: 영문과 숫자를 포함해 10자 이상(Inputs.password).
# base64 결과에 숫자가 없을 수 있어서 확인하고 다시 뽑는다. 숫자를 끼워 넣지 않는 것은
# 그렇게 하면 "마지막 글자가 항상 숫자"라는 규칙이 생겨 엔트로피가 줄기 때문이다.
PASSWORD=""
i=0
while [ "$i" -lt 20 ]; do
  candidate=$(openssl rand -base64 18 | tr -d '\n/+=')
  case "$candidate" in
    *[0-9]*) case "$candidate" in *[A-Za-z]*) PASSWORD="$candidate"; break ;; esac ;;
  esac
  i=$((i + 1))
done
[ -n "$PASSWORD" ] || { echo "비밀번호를 만들지 못했습니다." >&2; exit 1; }

umask 077
set_value() {
  key="$1"; value="$2"
  tmp="$TARGET.tmp"
  if grep -q "^${key}=" "$TARGET"; then
    # 구분자는 |. base64에서 /와 +를 이미 지웠고 |는 나오지 않는다.
    sed "s|^${key}=.*|${key}=${value}|" "$TARGET" > "$tmp"
  else
    cat "$TARGET" > "$tmp"
    printf '%s=%s\n' "$key" "$value" >> "$tmp"
  fi
  mv "$tmp" "$TARGET"
  chmod 600 "$TARGET"
}

set_value OPERATOR_BOOTSTRAP_EMAIL "$EMAIL"
set_value OPERATOR_BOOTSTRAP_PASSWORD "$PASSWORD"

cat <<NOTE

  이메일    $EMAIL
  비밀번호  $PASSWORD

이 비밀번호는 지금 한 번만 보입니다. 비밀번호 관리자에 넣으세요.

다음 순서로 마무리합니다.

  1) 재기동
     docker compose -f docker-compose.yml -f docker-compose.prod.yml \\
       --env-file $TARGET up -d

  2) 로그인해서 /admin 에 "운영자 콘솔" 버튼이 보이는지 확인

  3) $TARGET 에서 OPERATOR_BOOTSTRAP_ 두 줄을 지우고 한 번 더 재기동
     지우지 않아도 운영자가 있으면 무시되지만, 비밀번호가 파일에 남습니다.

NOTE
