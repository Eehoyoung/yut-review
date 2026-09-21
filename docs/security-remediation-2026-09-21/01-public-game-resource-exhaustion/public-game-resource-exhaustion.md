# 공개 게임 생성 자원 고갈

## Executive Summary
**High / 신뢰도 높음.** QR 토큰만 가진 비인증 사용자가 새 전화번호와 idempotency key를 반복 제출해 매장별 게임·쿠폰 행과 저장공간을 증가시킬 수 있다.

## Background
`PublicController`와 `CoreServices`가 공개 게임 생성 경로를 제공하며 DB 저장은 `Repositories`에 위임한다.

## Vulnerability Details
`PublicController.java:15-18`, `CoreServices.java:289-296`, `Repositories.java:19-22`에서 매장 잠금 후 검증된 요청마다 `GamePlay`/`Coupon`을 삽입한다. 전화번호·키를 바꾸면 기존 cooldown/idempotency를 우회한다.

## Exploitability Analysis
QR을 알고 있는 원격 공격자가 자동화 요청으로 재현 가능하다. 실제 운영 용량과 다중 복제본은 확인하지 못했으므로 영향 규모는 미검증이다.

## Proof of Concept
실행 PoC는 수행하지 않았다. 재현 조건은 공개 QR, 반복 가능한 신규 phone/idempotency 값, 요청 자동화다.

## Remediation
매장·IP·고객 식별자별 지속형 rate limit과 일/월 생성 quota, DB/스토리지 상한 및 초과 시 `429`를 도입한다. 고객키별 잠금과 동일 고객의 비정상 신규 식별자 탐지를 추가하되, 정상 고객의 QR→게임 진입과 2일 cooldown 규칙은 유지한다. 부하 테스트로 행 증가·응답시간·429 경계를 검증한다.

## Summary
P0로 우선 완화 후 재현 부하 테스트와 운영 알림을 추가한다.
