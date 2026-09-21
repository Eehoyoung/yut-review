# 운영 Compose의 Nginx 설정 선택 오류

## Executive Summary
**Medium.** Compose는 항상 `nginx/default.conf`와 HTTP 포트만 마운트해 prod profile을 켜도 TLS/HSTS/canonical origin이 적용되지 않는다.
## Background
`docker-compose.yml:64-69`, `nginx/default.conf`, `nginx/production.conf`, `application-prod.yml`.
## Vulnerability Details
운영자가 prod backend만 선택하면 cleartext ingress와 field-test server_name이 남을 수 있다.
## Exploitability Analysis
배포 경로를 따른 운영 환경에서 네트워크 도청·Host 혼동 위험이 생긴다. 외부 TLS terminator 존재는 미검증이다.
## Proof of Concept
실행 PoC 없음.
## Remediation
명시적 production Compose override/profile에서 `production.conf`, 인증서 read-only mount, 80→443 redirect, canonical host, HSTS를 강제하고 필수값 없으면 fail closed한다. field-test bootstrap과 prod를 분리하고 Compose 재빌드·TLS curl 검증을 CI에 둔다.
## Summary
P1. 실제 배포 파일과 문서를 같은 ingress 선택으로 통합한다.
