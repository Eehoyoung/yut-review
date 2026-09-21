# Prize CSV 수식 주입

## Executive Summary
**Low.** prize name이 `=,+,-,@` 등으로 시작하면 CSV quoting만으로는 spreadsheet 수식이 비활성화되지 않는다.
## Background
`AnalyticsService.java:102-123`, `AdminController.java:42-46,63-69`.
## Vulnerability Details
관리자가 악성 prize name을 저장한 뒤 CSV를 열면 외부 링크·명령형 수식이 실행될 수 있다.
## Exploitability Analysis
주로 tenant 관리자와 파일 수신자 사이의 신뢰 경계 문제다. 실제 spreadsheet 종류는 미검증이다.
## Proof of Concept
실행 PoC 없음.
## Remediation
모든 텍스트 셀에서 위험 선행문자를 apostrophe 등으로 중화한 뒤 RFC4180 quoting을 적용한다. `=,+,-,@,tab,CR`, 따옴표·개행 테스트를 추가한다.
## Summary
P2. export 공통 encoder로 수정한다.
