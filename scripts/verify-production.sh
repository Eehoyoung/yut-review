#!/bin/sh
# 배포 후 운영 경로 점검.
#
# 정적 검토로는 확인할 수 없던 것들 — 실제 TLS 인증서, DNS, Cloudflare 앞단, 보안 헤더,
# fail-closed 동작 — 을 살아 있는 origin에 대고 확인한다. 배포 직후 한 번, 그리고 Cloudflare
# 설정이나 인증서를 바꿀 때마다 다시 돌린다.
#
#   sh scripts/verify-production.sh                      # https://hanpan.sodamlabs.kr
#   sh scripts/verify-production.sh https://staging.example
#   ORIGIN_IP=1.2.3.4 sh scripts/verify-production.sh    # Cloudflare를 우회해 origin 직접 점검
#
# 읽기 전용이다. 아무 것도 만들지 않고 아무 것도 바꾸지 않는다.
set -u

ORIGIN="${1:-https://hanpan.sodamlabs.kr}"
HOST=$(printf '%s' "$ORIGIN" | sed -e 's|^https\{0,1\}://||' -e 's|/.*$||')
PASS=0
FAIL=0
WARN=0

ok()   { PASS=$((PASS+1)); printf '  [PASS] %s\n' "$1"; }
bad()  { FAIL=$((FAIL+1)); printf '  [FAIL] %s\n' "$1"; }
warn() { WARN=$((WARN+1)); printf '  [WARN] %s\n' "$1"; }
section() { printf '\n== %s ==\n' "$1"; }

command -v curl >/dev/null 2>&1 || { echo "curl이 필요합니다." >&2; exit 2; }

printf 'origin: %s\n' "$ORIGIN"

# ---------------------------------------------------------------- DNS
section "DNS / Cloudflare"
if command -v dig >/dev/null 2>&1; then
  ADDRS=$(dig +short "$HOST" A | tr '\n' ' ')
  [ -n "$ADDRS" ] && ok "A 레코드: $ADDRS" || bad "A 레코드를 찾을 수 없습니다"
else
  warn "dig가 없어 DNS 확인을 건너뜁니다"
fi

# Cloudflare를 지나면 cf-ray가 붙는다. 없으면 DNS가 회색 구름(프록시 꺼짐)이거나
# 레코드가 origin을 직접 가리키고 있다는 뜻이고, 그러면 origin IP가 그대로 노출된다.
HEADERS=$(curl -sS -D - -o /dev/null --max-time 15 "$ORIGIN/" 2>/dev/null)
if printf '%s' "$HEADERS" | grep -qi '^cf-ray:'; then
  ok "Cloudflare 프록시를 지납니다 (cf-ray 있음)"
else
  bad "cf-ray가 없습니다. DNS 레코드가 프록시(주황 구름) 상태인지 확인하세요"
fi

# ---------------------------------------------------------------- TLS
section "TLS"
if command -v openssl >/dev/null 2>&1; then
  CHAIN=$(printf 'Q' | openssl s_client -connect "$HOST:443" -servername "$HOST" 2>/dev/null)
  printf '%s' "$CHAIN" | grep -q "Verify return code: 0 (ok)" \
    && ok "인증서 체인 검증 통과" \
    || bad "인증서 체인 검증 실패 (Verify return code 확인)"
  printf '%s' "$CHAIN" | grep -q "TLSv1.3\|TLSv1.2" \
    && ok "TLS 1.2/1.3" || warn "TLS 버전을 확인하지 못했습니다"
else
  warn "openssl이 없어 인증서 확인을 건너뜁니다"
fi

# Cloudflare SSL mode가 Full (strict)인지는 origin 쪽에서만 확인된다.
# ORIGIN_IP를 주면 Cloudflare를 우회해 origin 인증서를 직접 본다.
#
# 여기서 공인 CA 신뢰 저장소로 검증하면 안 된다. 권장 구성인 Cloudflare Origin Certificate는
# 공인 CA가 서명하지 않아 curl 기본 검증이 항상 "unable to get local issuer certificate"로 죽는다.
# Full (strict)가 보는 것은 공인 신뢰가 아니라 "Cloudflare Origin CA 또는 공인 CA로 검증되는가"다.
# 그래서 Origin CA 루트를 받아 그것으로 한 번 더 본다.
CF_ORIGIN_ROOT_URL=https://developers.cloudflare.com/ssl/static/origin_ca_rsa_root.pem
if [ -n "${ORIGIN_IP:-}" ]; then
  # 여기서 실패하는 것은 Origin Certificate의 정상 동작이라 stderr를 버린다.
  if curl -sS -o /dev/null --max-time 15 --resolve "$HOST:443:$ORIGIN_IP" "https://$HOST/" 2>/dev/null; then
    ok "origin($ORIGIN_IP)이 공인 CA 인증서로 직접 응답합니다 (Full strict 가능)"
  else
    CF_ROOT=$(mktemp)
    if curl -sS --max-time 15 -o "$CF_ROOT" "$CF_ORIGIN_ROOT_URL" 2>/dev/null &&
       curl -sS -o /dev/null --max-time 15 --cacert "$CF_ROOT"             --resolve "$HOST:443:$ORIGIN_IP" "https://$HOST/"; then
      ok "origin($ORIGIN_IP)이 Cloudflare Origin CA 인증서로 직접 응답합니다 (Full strict 가능)"
    else
      # 신뢰 검증을 빼고도 붙는지 본다. 붙으면 인증서 문제, 안 붙으면 리스너/방화벽 문제다.
      if curl -sSk -o /dev/null --max-time 15 --resolve "$HOST:443:$ORIGIN_IP" "https://$HOST/" 2>/dev/null; then
        bad "origin($ORIGIN_IP)의 TLS는 붙지만 어느 CA로도 검증되지 않습니다. Full (strict)에서 526이 납니다"
      else
        bad "origin($ORIGIN_IP) 443에 연결되지 않습니다. 방화벽이나 nginx 리스너를 보세요 (522)"
      fi
    fi
    rm -f "$CF_ROOT"
  fi
else
  warn "ORIGIN_IP를 주지 않아 origin 인증서를 직접 확인하지 못했습니다 (Full strict 미확인)"
fi

# ---------------------------------------------------------------- 리다이렉트와 헤더
section "HTTP → HTTPS"
CODE=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "http://$HOST/" 2>/dev/null)
case "$CODE" in
  301|308) ok "평문 요청이 $CODE 로 리다이렉트됩니다" ;;
  200)     bad "평문 요청이 200으로 응답합니다. HTTPS 강제가 걸려 있지 않습니다" ;;
  *)       warn "평문 요청 응답 코드: $CODE" ;;
esac

section "보안 헤더"
need_header() {
  name="$1"; want="$2"
  line=$(printf '%s' "$HEADERS" | grep -i "^$name:" | head -1 | tr -d '\r')
  if [ -z "$line" ]; then bad "$name 없음"; return; fi
  if [ -n "$want" ] && ! printf '%s' "$line" | grep -qi -- "$want"; then
    bad "$name 값에 '$want' 가 없습니다: $line"; return
  fi
  ok "$name"
}
need_header "strict-transport-security" "max-age="
need_header "x-content-type-options" "nosniff"
need_header "x-frame-options" "DENY"
need_header "referrer-policy" ""
need_header "permissions-policy" ""
# CSP에서 이 둘이 빠지면 화면이 흰 채로 뜨고 콘솔 에러도 남지 않는다.
need_header "content-security-policy" "unsafe-inline"
need_header "content-security-policy" "wasm-unsafe-eval"

# ---------------------------------------------------------------- fail-closed
section "fail-closed"
# 알 수 없는 Host로 오는 요청은 default_server가 444로 끊는다. Cloudflare를 지나면
# Cloudflare가 먼저 거를 수 있으므로 origin 직결일 때만 의미가 있다.
if [ -n "${ORIGIN_IP:-}" ]; then
  UNKNOWN=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 \
    --resolve "unknown.invalid:443:$ORIGIN_IP" "https://unknown.invalid/" 2>/dev/null)
  if [ -z "$UNKNOWN" ] || [ "$UNKNOWN" = "000" ]; then
    ok "알 수 없는 Host는 응답 없이 끊깁니다 (444)"
  else
    bad "알 수 없는 Host가 $UNKNOWN 으로 응답합니다. default_server 확인"
  fi
else
  warn "ORIGIN_IP 없이 default_server(444) 동작을 확인할 수 없습니다"
fi

# 위조한 forwarded 헤더. 두 헤더는 막는 주체가 달라서 따로 본다.
#
# X-Real-IP: 손님이 보낼 수 있는 평범한 헤더다. 200이어야 정상이고, "무시한다"는 사실 자체는
#   밖에서 관측되지 않아 회귀 테스트가 담당한다
#   (SecurityRemediationTest.forwardedIpIsTrustedOnlyFromAConfiguredProxy).
# CF-Connecting-IP: Cloudflare가 자기 몫으로 쓰는 헤더라 엣지가 위조본을 403으로 끊는다.
#   origin에 닿지도 않는다. 200이 나오면 요청이 Cloudflare를 안 지났다는 뜻이다.
SPOOF=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15   -H 'X-Real-IP: 1.2.3.4' "$ORIGIN/" 2>/dev/null)
[ "$SPOOF" = "200" ] && ok "위조 X-Real-IP가 서비스를 깨뜨리지 않습니다"   || warn "위조 X-Real-IP 요청 응답 코드: $SPOOF"

CFSPOOF=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15   -H 'CF-Connecting-IP: 1.2.3.4' "$ORIGIN/" 2>/dev/null)
case "$CFSPOOF" in
  403) ok "위조 CF-Connecting-IP를 Cloudflare 엣지가 차단합니다 (403)" ;;
  200) warn "위조 CF-Connecting-IP가 200입니다. 요청이 Cloudflare를 지나지 않았을 수 있습니다" ;;
  *)   warn "위조 CF-Connecting-IP 응답 코드: $CFSPOOF" ;;
esac

# ---------------------------------------------------------------- 애플리케이션
section "애플리케이션"
HEALTH=$(curl -sS --max-time 15 "$ORIGIN/api/actuator/health" 2>/dev/null)
printf '%s' "$HEALTH" | grep -q '"status":"UP"' \
  && ok "health UP" || bad "health 응답이 UP이 아닙니다: $HEALTH"

# 운영은 canonical origin을 고정한다. 요청 Host로 QR/포스터 주소를 만들면 안 된다.
NOTFOUND=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 \
  "$ORIGIN/api/public/stores/by-token/definitely-not-a-real-token" 2>/dev/null)
[ "$NOTFOUND" = "404" ] && ok "없는 QR 토큰은 404" || warn "없는 QR 토큰 응답 코드: $NOTFOUND"

# ---------------------------------------------------------------- 서버 로컬 점검
section "서버 로컬 (이 스크립트를 운영 서버에서 돌릴 때만 의미 있음)"
# 한 호스트에 다른 프로젝트가 같이 떠 있을 수 있으므로 compose 프로젝트로 좁힌다.
COMPOSE_PROJECT="${COMPOSE_PROJECT:-yut-review}"
if command -v docker >/dev/null 2>&1 && docker ps >/dev/null 2>&1; then
  EXPOSED=$(docker ps --filter "label=com.docker.compose.project=$COMPOSE_PROJECT"     --format '{{.Names}} {{.Ports}}' | grep -E '0\.0\.0\.0:(5432|8080|3000)' || true)
  [ -z "$EXPOSED" ] && ok "postgres/backend/frontend가 host 포트를 열지 않았습니다" \
    || bad "host에 노출된 포트가 있습니다: $EXPOSED"
else
  warn "docker를 쓸 수 없어 포트 노출 확인을 건너뜁니다"
fi

printf '\n---\nPASS %d / FAIL %d / WARN %d\n' "$PASS" "$FAIL" "$WARN"
[ "$FAIL" -eq 0 ] || exit 1
