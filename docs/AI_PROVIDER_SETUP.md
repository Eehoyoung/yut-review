# OpenAI API 키 연결과 즉시 테스트

현재 코드는 외부 호출 직전 단계까지 연결돼 있다. 기본 `AI_PROVIDER=auto`는 `OPENAI_API_KEY`가 비어 있으면
안전한 fake 응답을 사용하고, 키가 있으면 OpenAI Responses API 구현을 자동 선택한다.

## 1. 키 연결

실제 환경 파일(`.env.field-test` 또는 `.env.production`)에 다음 값만 추가한다.

```dotenv
OPENAI_API_KEY=실제_API_키
```

모델을 바꿀 필요가 있을 때만 아래 값을 덮어쓴다.

```dotenv
AI_MODEL_FAST=gpt-4.1-mini
AI_MODEL_ANALYSIS=gpt-4.1
AI_MODEL_CHAT=gpt-4.1-mini
```

- 키를 저장소, 문서, 명령 출력 또는 채팅에 붙여 넣지 않는다.
- Compose가 `OPENAI_API_KEY`를 백엔드 컨테이너에 전달한다.
- 명시적으로 fake를 유지하려면 `AI_PROVIDER=fake`를 사용한다.

## 2. 재기동

Field test:

```powershell
docker compose --env-file .env.field-test --profile field-test up -d --build backend frontend nginx
```

Production은 기존 GHCR 배포 절차로 새 백엔드 이미지를 배포한다. 키만 바꾸더라도 컨테이너 환경을 다시
읽어야 하므로 백엔드 재생성이 필요하다.

## 3. 연결 상태 확인

관리자 로그인 후 매장 `AI 도우미` 화면을 연다.

- `API 키 연결됨`: OpenAI 공급자가 선택됐고 키가 설정됨. 키 유효성은 첫 실제 호출에서 확인
- `테스트 응답 모드`: 키가 없거나 `AI_PROVIDER=fake`

화면에는 공급자 이름과 모델 ID만 표시한다. API 키 값은 서버 응답과 로그에 포함하지 않는다.

## 4. 첫 실제 호출

- BASIC 또는 STANDARD: `AI 매장 분석` 실행
- PRO: `AI 이벤트 문구`, `AI 매장 분석`, `AI 개선 제안`, `AI 대화` 중 하나 실행

성공 시 사용량·모델·프롬프트 버전을 기록한다. 실패 시 차감한 쿼터를 환불하고 관리자 화면에
`AI_PROVIDER_UNAVAILABLE`, `AI_TIMEOUT` 또는 `AI_NOT_CONFIGURED`를 표시한다. 고객 QR·게임·쿠폰 흐름에는
영향을 주지 않는다.

