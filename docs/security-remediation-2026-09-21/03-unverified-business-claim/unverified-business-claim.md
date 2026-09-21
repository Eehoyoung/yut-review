# 사업자 통제권 미검증 셀프가입

## Executive Summary
**Medium / 신뢰도 높음.** 공개 signup이 형식·중복만 확인한 뒤 즉시 OWNER, PIN, QR을 발급한다.
## Background
`AdminController.java:22-32`, `CoreServices.java:60-76,182-200`이 가입과 권한 프로비저닝을 한 트랜잭션으로 처리한다.
## Vulnerability Details
사업자·이메일·도메인 통제 증명이 없어 타인의 상호/연락처를 선점하거나 분쟁을 유발할 수 있다.
## Exploitability Analysis
공개 가입 요청만으로 재현 가능하다. 실제 피해자 가장 여부는 런타임에서 확인하지 않았다.
## Proof of Concept
실행 PoC 없음. 임의 유효 형식의 signup이 OWNER를 얻는 경로가 근거다.
## Remediation
MVP 셀프가입은 유지하되 이메일/휴대전화 또는 운영자 승인 기반의 사업자 통제 증명, 보류 상태와 복구·분쟁 절차를 도입한다. 증명 전에는 민감 기능을 제한하고 소유권 변경을 감사 로그로 남긴다.
## Summary
P1. 가입 UX를 깨지 않는 단계적 검증과 회수 절차가 필요하다.
