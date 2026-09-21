# 전화번호 HMAC 키 강도 미검증

## Executive Summary
**Low.** `PHONE_HMAC_SECRET`는 임의 문자열을 HMAC 키로 사용하며 최소 길이·엔트로피 검증이 없다.
## Background
`CoreServices.java:17-22`, `application.yml:17-22`, `docker-compose.yml:25-27`.
## Vulnerability Details
약한 키와 DB 유출이 결합하면 전화번호 공간을 오프라인 열거해 참여 이력을 연결할 수 있다.
## Exploitability Analysis
DB와 약한 배포 secret 모두 필요하다. 현재 운영 secret 강도는 확인하지 않았다.
## Proof of Concept
실행 PoC 없음.
## Remediation
Base64로 인코딩한 무작위 32바이트 키를 요구하고 startup에서 디코딩·길이·형식 검증 실패 시 종료한다. 회전 시 기존 lookup hash 마이그레이션 계획과 secret 생성/보관 문서를 제공한다.
## Summary
P2. 키 정책을 AES/JWT 검증 수준으로 맞춘다.
