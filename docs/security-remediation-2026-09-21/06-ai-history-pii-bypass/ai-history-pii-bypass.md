# AI 대화 이력 PII 필터 우회

## Executive Summary
**Medium.** 현재 질문은 필터링하지만 클라이언트가 제공한 chat history는 그대로 모델 입력에 복사된다.
## Background
`AiService.java:188-215`, `AiContextService.java:226-238`, `LlmProvider.java:165-180`.
## Vulnerability Details
조작된 과거 메시지에 phone, token, JWT, API key 등 금지 데이터가 들어가면 provider로 전송될 수 있다.
## Exploitability Analysis
인증된 관리자 API 호출자가 직접 재현 가능하다. 실제 provider 전송은 설정과 호출이 필요하다.
## Proof of Concept
실행 PoC 없음.
## Remediation
모든 history turn을 중앙 PII/secret 정책으로 필터링하고 서버 소유 대화 이력만 사용한다. 금지 패턴과 coupon token/JWT/phoneLast4 조합을 확대하고 provider payload snapshot 테스트로 차단한다. 고객 API·AI 비중요성·aggregate only 규칙은 유지한다.
## Summary
P1. AI 경계에서 단일 sanitization pipeline을 강제한다.
