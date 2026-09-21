# 프록시 뒤 rate-limit 식별자 붕괴

## Executive Summary
**Medium.** Cloudflare/cloudflared 뒤에서 모든 사용자가 동일한 `X-Real-IP`로 집계되어 로그인·PIN 시도가 서로 차단된다.
## Background
`docker-compose.yml:81-87`, `nginx/default.conf:16-23`, `CoreServices.java:226-236`.
## Vulnerability Details
Nginx가 프록시 peer를 `X-Real-IP`로 덮고 애플리케이션 limiter가 이를 신뢰한다.
## Exploitability Analysis
공격자가 실패 요청을 반복하면 매장 PIN 또는 전체 로그인에 60초 거부를 유발할 수 있다.
## Proof of Concept
실행 PoC 없음. 프록시 토폴로지와 코드 경로를 정적으로 확인했다.
## Remediation
신뢰된 프록시 목록에서만 Cloudflare 연결 IP 헤더를 해석하고, 그 외에는 소켓 peer를 사용한다. Nginx real_ip 설정과 애플리케이션 검증을 일치시키며 IP 단독 키 대신 계정·매장·고객키별 보조 quota를 둔다. 프록시 시뮬레이션 테스트를 추가한다.
## Summary
P1. 공통 rate-limit 기반을 먼저 고쳐야 다른 완화책의 효과가 보장된다.
