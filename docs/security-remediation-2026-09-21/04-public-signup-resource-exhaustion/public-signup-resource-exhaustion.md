# 공개 가입 프로비저닝 자원 고갈

## Executive Summary
**Medium.** 공개 signup이 BCrypt, 다수 DB 행, 포스터 PNG 생성을 매번 수행하지만 공유 throttling이 없다.
## Background
`AdminController.java:31-32`, `CoreServices.java:60-76,182-200`, `StorePosterService.java:37-78`.
## Vulnerability Details
자동화 가입으로 CPU·DB·디스크를 소모하고 포스터 산출물을 누적시킬 수 있다.
## Exploitability Analysis
비인증 원격 자동화로 재현 가능하다. 실제 비용/한계는 미측정이다.
## Proof of Concept
실행 PoC 없음.
## Remediation
IP·계정·도메인별 rate limit과 일일 quota, 저비용 challenge를 BCrypt/포스터 생성 전에 적용한다. 포스터는 지연 생성·용량 상한·정리 정책을 사용하고 실패 시 전체 트랜잭션을 롤백한다. 정상 셀프가입은 유지한다.
## Summary
P1. 가입 전 단계 throttling과 디스크 상한을 먼저 적용한다.
