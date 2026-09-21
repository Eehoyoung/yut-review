# 로그인 limiter 무제한 client key 보존

## Executive Summary
**Low.** 로그인 시도 map은 unique IP key를 TTL/크기 제한 없이 보존한다.
## Background
`AdminController.java:32`, `CoreServices.java:232-236`.
## Vulnerability Details
분산·장기 실행 JVM에서 임의 IP를 대량 생성하면 메모리와 관리 비용이 증가한다.
## Exploitability Analysis
공격은 가능하지만 단일 인스턴스에서 즉시 장애가 발생하는지는 미검증이다.
## Proof of Concept
실행 PoC 없음.
## Remediation
로그인 limiter를 bounded cache로 바꾸고 idle TTL·최대 엔트리·주기적 purge를 적용한다. 가능하면 PIN limiter와 동일한 공유 저장소/키 정책을 사용하고 eviction 테스트를 추가한다.
## Summary
P2. 공통 rate-limit 컴포넌트 정리 시 함께 수정한다.
