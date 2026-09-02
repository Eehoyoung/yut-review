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
윷 4개를 각각 독립 RigidBody로 구성.

권장:
```text
Render Mesh: 고품질 GLB
Physics Collider: 단순 Convex/Capsule 계열
```
렌더링 mesh와 collider를 분리한다.

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

따라서 Hybrid Physics 사용.

```text
0~70%   실제 Rapier Physics
70~90%  속도/각속도 자연 감속
90~100% Target Quaternion 보정
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
