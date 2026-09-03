---
name: 윷 리뷰 이벤트
description: 매장의 신뢰를 지키며 윷 결과의 순간에만 에너지를 집중하는 시각 체계
colors:
  stage-green: "#16241F"
  terracotta: "#B34A20"
  terracotta-bright: "#D76535"
  wood: "#8A5320"
  wood-light: "#CFA273"
  paper: "#FFFFFF"
  cool-field: "#F4F7F5"
  muted-ink: "#4F5F58"
  hairline: "#DDE5E1"
typography:
  display:
    fontFamily: "Pretendard Variable, Apple SD Gothic Neo, Noto Sans KR, Malgun Gothic, system-ui, sans-serif"
    fontSize: "clamp(3.5rem, 22vw, 5.5rem)"
    fontWeight: 800
    lineHeight: 1
  headline:
    fontFamily: "Pretendard Variable, Apple SD Gothic Neo, Noto Sans KR, Malgun Gothic, system-ui, sans-serif"
    fontSize: "1.875rem"
    fontWeight: 800
    lineHeight: 1.2
  body:
    fontFamily: "Pretendard Variable, Apple SD Gothic Neo, Noto Sans KR, Malgun Gothic, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontFamily: "Pretendard Variable, Apple SD Gothic Neo, Noto Sans KR, Malgun Gothic, system-ui, sans-serif"
    fontSize: "0.8125rem"
    fontWeight: 700
rounded:
  sm: "10px"
  md: "14px"
  lg: "20px"
  full: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  xxl: "32px"
  jumbo: "48px"
components:
  button-primary:
    backgroundColor: "{colors.terracotta}"
    textColor: "{colors.paper}"
    rounded: "{rounded.md}"
    padding: "12px 16px"
    height: "54px"
  card:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.stage-green}"
    rounded: "{rounded.lg}"
    padding: "16px"
  field:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.stage-green}"
    rounded: "{rounded.md}"
    padding: "12px 16px"
    height: "52px"
  status-feedback:
    rounded: "{rounded.md}"
    padding: "12px 16px"
---

# Design System: 윷 리뷰 이벤트

## Overview

**Creative North Star: "고요한 매장 위의 한 번의 던짐"**

평소 화면은 차분한 매장 도구처럼 물러나고, 윷 결과와 참여 행동에서만 색과 크기가 강해진다. 인쇄물은 짙은 초록 무대를 가득 채운 뒤 흰색·테라코타 제목과 공중의 윷가락으로 그 순간을 압축한다. 웹 화면은 같은 초록 기운의 밝은 바탕과 동일한 테라코타·나무색을 사용한다.

정보보다 행동이 먼저 읽혀야 한다. A6 인쇄물의 큰 흰 QR 면처럼, 각 화면의 주 행동은 주변과 명확히 분리된 하나의 면 또는 버튼으로 표현한다.

**Key Characteristics:**

- 차분한 초록 중성 바탕과 제한된 테라코타 강조
- 굵고 짧은 한국어 제목, 조용한 보조 문장
- 윷가락의 길쭉한 캡슐 실루엣과 단단한 행동 면
- 사행성 장식 없이 결과 순간에만 집중되는 에너지

## Colors

짙은 초록과 서늘한 백색이 바탕을 만들고, 테라코타는 행동, 나무색은 윷과 보상에만 쓴다.

### Primary

- **Terracotta:** 참여 행동과 핵심 강조에 사용한다.
- **Bright Terracotta:** 짙은 무대 위 결과형 제목과 윷가락 한 개에 제한해 사용한다.

### Secondary

- **Wood:** 윷, 보상, 진행 상태에 사용한다.
- **Light Wood:** 윷가락의 물성과 명암 차이를 만든다.

### Neutral

- **Stage Green:** 인쇄물의 전면 무대와 웹의 가장 어두운 텍스트 기준이다.
- **Paper:** QR·카드·입력처럼 행동을 담는 면이다.
- **Cool Field:** 웹 화면의 조용한 기본 바탕이다.
- **Muted Ink:** 설명과 보조 정보에 사용한다.
- **Hairline:** 카드 경계와 구분선에만 사용한다.

**The One Accent Rule.** 한 화면의 주 행동색은 테라코타 하나다. 나무색은 윷과 보상 의미를 대신하지 않는다.

**The No Casino Rule.** 네온, 금화, 슬롯머신 색채와 과도한 금색은 사용하지 않는다.

## Typography

**Display Font:** Pretendard Variable 계열; 인쇄 환경에서는 맑은 고딕으로 대체한다.
**Body Font:** 같은 산세리프 계열을 유지한다.

**Character:** 굵은 제목은 결과와 행동을 즉시 읽히게 하고, 본문은 짧고 차분하게 물러난다. 서체 장식보다 굵기와 크기 차이로 위계를 만든다.

### Hierarchy

- **Display** (800, 유동 56-88px, 1): 게임 결과처럼 한 단어가 주인공인 순간에만 사용한다.
- **Headline** (800, 30px, 1.2): 화면 제목과 핵심 행동 문구에 사용한다.
- **Body** (400, 16px, 1.55): 안내와 설명에 사용한다.
- **Label** (700, 13px): 매장명, 필드명, 상태 라벨에 사용한다.

**The Short Headline Rule.** 큰 제목은 한 번에 읽히는 짧은 한국어 문구로 유지한다.

## Layout

웹의 고객 화면은 최대 460px 단일 열이며 4px 기반 간격 단계로 구성한다. 인쇄물은 A6 세로형에서 위쪽에 제목과 공중의 윷가락, 중앙에 가장 큰 QR 행동 면, 아래쪽에 테라코타 행동 막대를 둔다. QR은 47mm로 유지하고 흰 판 안에 충분한 여백을 확보한다.

**The One Action Plane Rule.** 한 장면에서 가장 중요한 행동은 가장 큰 독립 면 하나로 즉시 구분되어야 한다.

## Elevation & Depth

기본은 평면적이다. 웹 카드에는 경계를 분리하는 1px 선과 아주 얕은 그림자만 쓰고, 인쇄물은 그림자 없이 색면 대비와 겹침으로 깊이를 만든다. 공중의 윷가락은 서로 다른 회전과 색으로 움직임을 암시한다.

**The Flat-by-Default Rule.** 장식적 그림자로 주목을 만들지 않는다. 크기, 색면, 간격이 먼저다.

## Shapes

카드와 입력은 절제된 둥근 모서리를 사용하고, 윷가락은 양 끝이 완전히 둥근 긴 캡슐 형태를 유지한다. QR 판은 주변 요소보다 크게 둥글되 QR의 정사각형과 조용히 대비한다. 알약 형태는 상태와 짧은 내비게이션에만 사용한다.

## Components

### Buttons

- **Shape:** 단단하게 둥근 모서리(14px), 최소 높이 54px.
- **Primary:** 테라코타 면과 흰 글자, 좌우 16px·상하 12px 여백.
- **Active / Focus:** 더 짙은 테라코타와 미세한 눌림, 2px 테라코타 초점선을 사용한다.

### Cards / Containers

- **Corner Style:** 부드러운 큰 모서리(20px).
- **Background:** 흰색 행동 면.
- **Shadow Strategy:** 1px 경계와 최소 그림자만 사용한다.
- **Internal Padding:** 16px.

### Inputs / Fields

- **Style:** 흰 바탕, 1px 중성 경계, 14px 모서리, 최소 높이 52px.
- **Focus:** 2px 테라코타 외곽선과 2px 간격.

### QR Action Plate

A6 안내물의 중심 컴포넌트다. 47mm QR을 61×68mm 흰 판에 배치하고, 스캔 지시를 판 상단에 함께 묶는다. QR보다 장식이 먼저 보이지 않아야 한다.

### QR Poster Workspace

관리 화면은 서버에 저장된 A6 세로형 PNG 미리보기와 조작 영역을 나란히 두고, 700px 이하에서는 한 열로 접는다. 이미지 저장을 주 행동, 운영체제 공유 시트를 보조 행동으로 둔다. 파일 공유를 지원하지 않는 환경에서는 같은 저장 동작으로 자연스럽게 이어지며, 별도 공유 서비스 UI를 흉내 내지 않는다.

저장된 공개 주소는 줄바꿈 가능한 안내 면에 그대로 표시한다. 현재 접속 주소와 다르면 오류색 경고를 주소 가까이에 놓고, 현재 주소로 다시 만드는 행동을 바로 뒤에 제공한다.

### Status Feedback

완료 메시지는 성공색 면과 `role="status"`, 실패 및 주소 불일치는 오류색 면과 `role="alert"`로 표현한다. 진행 중에는 버튼 문구를 동사형 상태로 바꾸고 비활성화해 중복 동작을 막는다. 사용자가 공유를 취소한 경우에는 오류를 만들지 않는다.

## Do's and Don'ts

### Do:

- **Do** 매장명, 결과, QR처럼 현장에서 필요한 정보가 첫 시선에 읽히게 한다.
- **Do** 짙은 초록 위에는 흰색 또는 밝은 테라코타로 충분한 대비를 만든다.
- **Do** 인쇄 QR 교체 경고를 7.2pt 이상과 안전 여백 안에 유지한다.
- **Do** 다운로드·공유·재생성 결과를 행동 영역 바로 아래에서 짧은 상태 문장으로 확인시킨다.
- **Do** 저장된 주소와 현재 주소가 다르면 주소, 경고, 재생성 행동을 한 흐름으로 배치한다.

### Don't:

- **Don't** 카지노, 잭팟, 대박 같은 사행성 언어나 장식을 사용한다.
- **Don't** 테라코타와 나무색을 여러 행동에 경쟁시키지 않는다.
- **Don't** QR 주변 여백을 장식이나 문구로 침범하지 않는다.
- **Don't** 카카오톡이나 메일 전용 버튼을 만들지 않는다. 가능한 앱 선택은 운영체제 공유 시트에 맡긴다.
