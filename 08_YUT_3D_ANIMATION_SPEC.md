# 3D 윷놀이 애니메이션 기술 명세

## 목표
단순 이미지 회전/GIF가 아니라 실제로 윷 4개가 공중에서 회전하고 바닥에 충돌하여 튀고 굴러 정지하는 고품질 연출을 구현한다.

## Stack
```text
Three.js
React Three Fiber
@react-three/drei
Rapier
GLTF/GLB
Web Audio API
```

## 모델
전통 장작윷 기준: 길이 15~20cm, 지름 3~5cm, 단면은 반달(등=둥근 면, 배=평평한 면)이며
배면은 잘 구르도록 약간 볼록하게 다듬는다. 씬 단위는 1 unit ≈ 8.7cm로 잡아 길이 2.3, 반지름 0.22를 쓴다.

- Render Mesh: 반달 단면을 길이 방향으로 압출(ExtrudeGeometry), 배는 밝은 나무색·등은 어두운 색(vertex color)
- Physics Collider: 같은 단면의 convex hull
- 윷 4개는 각각 독립 강체이며 서로의 시뮬레이션에 영향을 주지 않는다.

## State Machine
```text
IDLE
READY
THROW
AIR
FIRST_IMPACT
BOUNCE
ROLL
SETTLE
RESULT_LOCK
RESULT_REVEAL
```

## THROW
각 윷에 seed 기반으로 서로 다른 값을 준다.
- 위치 offset
- linear impulse
- angular velocity
- rotation axis

## AIR
공중에서 충분히 복잡한 회전을 발생시킨다.

## IMPACT / BOUNCE
- 첫 충돌에 가장 강한 wood impact sound
- 이후 1~3회 자연스러운 bounce
- 4개 윷의 충돌 타이밍이 완전히 동일하지 않게 구성

## ROLL
마찰로 점차 감속.

## 서버 결과 동기화
순수 Physics만 사용하면 서버가 `MO`를 결정했는데 실제 물리 결과가 다른 면으로 정지할 수 있다.

**착지 후 보정 금지.** 결과를 받은 뒤 화면에서 회전시키거나 위치를 옮기면 고객 눈에 조작으로 보인다.

채택한 구조는 **선(先)시뮬레이션 후(後)재생**이다.

1. 던지기 버튼 → `reveal()`로 서버 결과 확보
2. 화면에 아무것도 그리기 전에 윷 4개를 **각각 독립 월드에서 정지할 때까지 시뮬레이션**
3. 정지 자세의 배/등이 서버 결과와 다르면 그 윷만 다시 던져 재시뮬레이션(윷당 최대 24회)
4. 결과가 일치하는 실행의 **매 스텝 위치·회전을 기록**
5. 화면은 그 기록을 60Hz 기준으로 보간 재생하고, 마지막 프레임에서 그대로 멈춘다

따라서 화면에는 물리 엔진이 돌지 않는다. 프레임이 끊겨도 결과·위치가 달라질 수 없고,
착지한 면과 자리가 그대로 최종 결과다. 실시간 물리 + 사후 보정 방식으로 되돌리지 말 것.

따라서 Hybrid Physics 사용.

```text
throw    목표 면을 노린 X축 각속도(앞뒤 텀블링) + 상방 속도, Z축(긴 축) 회전 없음
air      Rapier 실제 물리
impact   멍석 흡수 모사: 첫 접촉에서 각속도 92%, 선속도 65% 감쇠 -> 착지 면 보존
roll     마찰로 감속
settle   선속도/각속도 임계 이하가 연속 유지되면 정지 판정
verify   정지 자세가 서버 결과와 일치하는지 검사, 불일치 시 그 윷만 재시뮬레이션
replay   기록된 프레임 재생 -> 보정 없음
```

마지막 보정은 `Quaternion.slerp()` 등을 사용해 0.3~0.6초 사이 자연스럽게 적용한다.

## 결과 면 구성
프로젝트 내부 표준:
```text
도  = 앞 1 / 뒤 3
개  = 앞 2 / 뒤 2
걸  = 앞 3 / 뒤 1
윷  = 앞 4 / 뒤 0
모  = 앞 0 / 뒤 4
```
실제 GLB 모델의 local axis와 앞/뒤 정의를 문서화한다.

## animationSeed
서버가 결과와 별도로 `animationSeed`를 발급한다.

Seed 사용 대상:
- 4개 윷 시작 위치
- impulse variance
- angular velocity
- bounce variance
- camera micro shake
- particle variation

당첨 결과 자체는 seed가 아니라 DB 저장값이 Source of Truth다.

## 카메라
추천 sequence:
1. 시작: 낮은 3/4 view
2. 던질 때 약한 follow
3. 첫 충돌 때 micro shake
4. 정지 직전 살짝 top-down 이동
5. 결과가 한눈에 보이는 각도에서 lock

과도한 카메라 이동 금지.

## Lighting
- Hemisphere/Ambient
- Directional Key Light
- Soft Shadow
- 모바일 성능에 따라 HDRI 선택

## Sound
```text
throw
wood_hit_1
wood_hit_2
wood_roll
result_normal
result_high
result_mo
```

## Tier별 Effect
### Tier 1
- 최소한의 결과 강조

### Tier 2
- 가벼운 glow/particle

### Tier 3 / 모
- 짧은 confetti
- result glow
- 작은 scale pulse
- 특별 sound

카지노/도박 느낌의 과도한 이펙트는 피한다.

## 성능 목표
```text
중급 모바일: 45~60 FPS
저사양 모바일: 최소 30 FPS
```

## 필수 테스트
- iOS Safari
- Android Chrome
- Samsung Internet
- 탭 전환/복귀
- 저전력/낮은 FPS
- 게임 도중 새로고침
- 모든 5개 결과의 최종 면 정확성
