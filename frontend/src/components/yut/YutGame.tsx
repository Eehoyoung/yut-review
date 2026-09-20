"use client";

import { Canvas, useFrame } from "@react-three/fiber";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { BufferAttribute, Color, ExtrudeGeometry, Quaternion, Shape, type Mesh } from "three";

import type { RevealResponse } from "@/types/api";
import { frontFacesFor, type YutResult } from "@/features/game/yut-result";
import { crossSection, STICK_LENGTH, STICK_RADIUS } from "@/features/game/yut-shape";
import { LANE_SPACING, simulateThrow, spreadFaces, STEP_HZ, warmUpPhysics, type ThrowRecording } from "@/features/game/yut-throw";
import { YUT_LABEL } from "@/features/labels";

type Phase = "READY" | "THROW" | "AIR" | "IMPACT" | "ROLL" | "SETTLE" | "RESULT_LOCK" | "REVEAL";

type Props = {
  playId: string;
  animationSeed: string;
  reveal: () => Promise<RevealResponse>;
  onRevealed?: (result: RevealResponse) => void;
  className?: string;
};

const BELLY_COLOR = new Color("#f0d9ab");
const BACK_COLOR = new Color("#8f5c2c");

/** Half-moon cross section extruded along the stick, belly (+Y) light and back dark. */
function useStickGeometry() {
  return useMemo(() => {
    const section = crossSection();
    const shape = new Shape();
    shape.moveTo(section[0][0], section[0][1]);
    for (const [x, y] of section.slice(1)) shape.lineTo(x, y);
    shape.closePath();

    const geometry = new ExtrudeGeometry(shape, { depth: STICK_LENGTH, bevelEnabled: false, curveSegments: 14 });
    geometry.translate(0, 0, -STICK_LENGTH / 2);
    geometry.computeVertexNormals();

    const position = geometry.getAttribute("position");
    const colors = new Float32Array(position.count * 3);
    for (let i = 0; i < position.count; i += 1) {
      const color = position.getY(i) > 0.001 ? BELLY_COLOR : BACK_COLOR;
      colors[i * 3] = color.r;
      colors[i * 3 + 1] = color.g;
      colors[i * 3 + 2] = color.b;
    }
    geometry.setAttribute("color", new BufferAttribute(colors, 3));
    return geometry;
  }, []);
}

function Sticks({
  recording,
  shadows,
  reducedMotion,
  onImpact,
  onSettled,
  meshes,
}: {
  recording?: ThrowRecording;
  shadows: boolean;
  reducedMotion: boolean;
  onImpact: () => void;
  onSettled: () => void;
  meshes: { current: (Mesh | null)[] };
}) {
  const geometry = useStickGeometry();
  const started = useRef<number | undefined>(undefined);
  const impacted = useRef(false);
  const settled = useRef(false);
  const from = useRef(new Quaternion());
  const to = useRef(new Quaternion());

  useEffect(() => {
    started.current = undefined;
    impacted.current = false;
    settled.current = false;
  }, [recording]);

  // Pure playback of the verified recording: no physics runs here, so a slow frame can only
  // make the replay smoother or choppier, never change where a stick stops or which face shows.
  useFrame(({ clock }) => {
    if (!recording) return;

    // 감소 모션: 시뮬레이션은 그대로 돌리되(착지 면은 이미 검증됐다) 재생만 건너뛰고
    // 마지막(정지) 프레임을 바로 적용한다. onImpact 소리·중간 페이즈는 생략하고 onSettled로 바로 간다.
    if (reducedMotion) {
      if (settled.current) return;
      const last = recording.frames[recording.frames.length - 1];
      last.forEach((frame, stick) => {
        const mesh = meshes.current[stick];
        if (!mesh) return;
        mesh.position.set(frame.p[0], frame.p[1], frame.p[2]);
        mesh.quaternion.set(frame.q[0], frame.q[1], frame.q[2], frame.q[3]);
      });
      settled.current = true;
      onSettled();
      return;
    }

    started.current ??= clock.elapsedTime;
    const elapsed = (clock.elapsedTime - started.current) * STEP_HZ;
    const last = recording.frames.length - 1;
    const index = Math.min(Math.floor(elapsed), last);
    const next = Math.min(index + 1, last);
    const alpha = Math.min(1, Math.max(0, elapsed - index));

    recording.frames[index].forEach((frame, stick) => {
      const mesh = meshes.current[stick];
      if (!mesh) return;
      const ahead = recording.frames[next][stick];
      mesh.position.set(
        frame.p[0] + (ahead.p[0] - frame.p[0]) * alpha,
        frame.p[1] + (ahead.p[1] - frame.p[1]) * alpha,
        frame.p[2] + (ahead.p[2] - frame.p[2]) * alpha,
      );
      from.current.set(frame.q[0], frame.q[1], frame.q[2], frame.q[3]);
      to.current.set(ahead.q[0], ahead.q[1], ahead.q[2], ahead.q[3]);
      mesh.quaternion.copy(from.current.slerp(to.current, alpha));
    });

    if (!impacted.current && elapsed >= recording.impactStep) {
      impacted.current = true;
      onImpact();
    }
    if (!settled.current && index >= last) {
      settled.current = true;
      onSettled();
    }
  });

  return (
    <>
      {[0, 1, 2, 3].map((index) => (
        <mesh
          key={index}
          ref={(instance) => {
            meshes.current[index] = instance;
          }}
          geometry={geometry}
          position={[(index - 1.5) * LANE_SPACING, STICK_RADIUS, 0.6]}
          castShadow={shadows}
          receiveShadow={shadows}
        >
          <meshStandardMaterial vertexColors roughness={0.78} metalness={0.02} />
        </mesh>
      ))}
    </>
  );
}

/**
 * 판은 서비스 색인 테라코타 계열의 아주 어두운 톤이다. 초록에서 옮겨오면서 윷도 더 잘 보인다.
 * 어두운 등(#8f5c2c) 대비가 2.15:1에서 3.10:1로 올라간다. 판은 배경이므로 물러나 있어야 하고,
 * 주인공은 윷이다.
 */
function Mat({ shadows }: { shadows: boolean }) {
  return (
    <mesh position={[0, -0.09, 0]} receiveShadow={shadows}>
      <cylinderGeometry args={[4.2, 4.5, 0.18, 48]} />
      <meshStandardMaterial color="#2a140b" roughness={0.94} />
    </mesh>
  );
}

const PHASE_LABEL: Record<Phase, string> = {
  READY: "윷을 던져 주세요",
  THROW: "던지는 중",
  AIR: "날아가는 중",
  IMPACT: "탁!",
  ROLL: "구르는 중",
  SETTLE: "멈추는 중",
  RESULT_LOCK: "결과 확인 중",
  REVEAL: "결과 확인",
};

export default function YutGame({ playId, animationSeed, reveal, onRevealed, className }: Props) {
  const [phase, setPhase] = useState<Phase>("READY");
  const [result, setResult] = useState<RevealResponse>();
  const [recording, setRecording] = useState<ThrowRecording>();
  const [error, setError] = useState("");
  const [preparing, setPreparing] = useState(false);
  const timers = useRef<number[]>([]);
  const meshes = useRef<(Mesh | null)[]>([null, null, null, null]);

  // Weak phones skip shadows; DPR stays capped for mobile Safari.
  const shadows = useMemo(() => typeof navigator === "undefined" || (navigator.hardwareConcurrency ?? 4) >= 6, []);
  // PRODUCT.md: 감소 모션에서는 텀블링 대신 정지 자세를 즉시 보여준다. 결과는 어떤 경우에도 텍스트로 읽힌다.
  const reducedMotion = useMemo(
    () => typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches,
    [],
  );

  // 손님이 화면을 읽는 몇 초 동안 WASM fetch+compile을 미리 끝내 둔다. 던지기 탭 이후 시작하면
  // 그 시간이 고스란히 첫 던지기 지연으로 붙는다. 실패해도 던질 때 simulateThrow가 다시 await 한다.
  useEffect(() => {
    void warmUpPhysics().catch(() => {});
  }, []);

  const sound = useCallback((frequency: number, duration = 0.08) => {
    try {
      const context = new AudioContext();
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.frequency.value = frequency;
      gain.gain.setValueAtTime(0.07, context.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, context.currentTime + duration);
      oscillator.connect(gain).connect(context.destination);
      oscillator.start();
      oscillator.stop(context.currentTime + duration);
      oscillator.addEventListener("ended", () => void context.close());
    } catch {
      // Audio is optional; muted or autoplay-restricted devices keep the full visual flow.
    }
  }, []);

  useEffect(() => () => timers.current.forEach(window.clearTimeout), []);

  const schedule = useCallback((next: Phase, delay: number) => {
    timers.current.push(window.setTimeout(() => setPhase(next), delay));
  }, []);

  // Result first, then a throw that is simulated to rest and checked against it. Only a run that
  // ends on the server's faces is ever shown, so nothing has to be corrected on screen.
  const throwYut = useCallback(async () => {
    if (phase !== "READY" || preparing) return;
    setError("");
    setPreparing(true);
    let revealed: RevealResponse;
    try {
      revealed = await reveal();
    } catch {
      // reveal 자체가 실패하면 아직 쿠폰이 발급되지 않았을 수 있으니 재시도할 수 있게 둔다.
      setError("결과를 확인하지 못했어요. 다시 던져 주세요.");
      setPreparing(false);
      return;
    }
    try {
      const faces = spreadFaces(animationSeed, frontFacesFor(revealed.yutResult as YutResult));
      const throwRecording = await simulateThrow(`${animationSeed}:${revealed.playId}`, faces);
      setResult(revealed);
      setRecording(throwRecording);
      sound(180, 0.06);
      setPhase("THROW");
      if (!reducedMotion) schedule("AIR", 160);
    } catch {
      // reveal은 이미 성공해 쿠폰이 발급됐다. 연출(3D)이 실패했다고 결과 도달까지 막으면 안 된다.
      onRevealed?.(revealed);
    } finally {
      setPreparing(false);
    }
  }, [animationSeed, onRevealed, phase, preparing, reducedMotion, reveal, schedule, sound]);

  const onImpact = useCallback(() => {
    setPhase("IMPACT");
    sound(92, 0.12);
    schedule("ROLL", 380);
    schedule("SETTLE", 900);
  }, [schedule, sound]);

  const onSettled = useCallback(() => {
    setPhase("RESULT_LOCK");
    timers.current.push(
      window.setTimeout(() => {
        setPhase("REVEAL");
        if (result) {
          sound(result.yutResult === "MO" ? 660 : 440, 0.18);
          timers.current.push(window.setTimeout(() => onRevealed?.(result), 1100));
        }
      }, 600),
    );
  }, [onRevealed, result, sound]);

  const idle = phase === "READY" && !preparing;
  const buttonLabel = preparing ? "결과 확인 중" : error ? "다시 던지기" : phase === "READY" ? "윷 던지기" : "던지는 중";

  return (
    <section className={className ? `stage ${className}` : "stage"} aria-label={`윷놀이 ${playId}`}>
      <div className="stage-top">
        <nav className="steps" aria-label="참여 단계">
          <span>1 정보 입력</span>
          <i data-on="1" />
          <b>2 윷 던지기</b>
          <i />
          <span>3 쿠폰</span>
        </nav>
        {/* 페이즈 문구는 8단계가 줄줄이 낭독되면 소음이 된다. 낭독은 실제 결과 하나로 충분하다. */}
        <p className="stage-phase">{PHASE_LABEL[phase]}</p>
      </div>

      <div className="stage-canvas">
        {/*
          카메라는 실제 착지 범위(x -1.94~2.02, z -2.90~3.80, 40번 던져 실측)를 폰 세로 화면에
          전부 담도록 맞췄다. 이전 [0, 5.4, 6.2]에서는 아이폰 12 기준 가로가 1.62배 넘쳐
          던지기 10번 중 9번꼴로 윷 한 짝 이상이 화면 밖으로 나갔다. 던져진 모양이 곧 결과인
          게임에서 보이지 않는 윷은 작은 윷보다 나쁘다.
          R3F가 기본으로 원점을 바라보므로 위치만 바꾸면 된다. 이 값을 줄이면 다시 잘린다.
        */}
        <Canvas shadows={shadows} camera={{ position: [0, 9.86, 5.69], fov: 40 }} dpr={[1, 1.5]} gl={{ antialias: false, powerPreference: "high-performance" }}>
          <Suspense fallback={null}>
            <ambientLight intensity={1.2} />
            <directionalLight position={[3, 7, 4]} intensity={2.3} castShadow={shadows} shadow-mapSize={[512, 512]} />
            <Mat shadows={shadows} />
            <Sticks
              recording={recording}
              shadows={shadows}
              reducedMotion={reducedMotion}
              onImpact={onImpact}
              onSettled={onSettled}
              meshes={meshes}
            />
          </Suspense>
        </Canvas>
      </div>

      <div className="stage-bottom">
        {/*
          라이브 리전은 처음부터 자리를 잡고 있어야 한다. 결과 시점에 요소째 새로 넣으면
          iOS VoiceOver가 삽입 자체를 놓쳐 결과를 읽지 않고 지나간다. 눈으로 읽는 결과는
          아래 .stage-result가 맡고, 이 줄은 낭독만 맡는다.
        */}
        <p className="visually-hidden" role="status">
          {phase === "REVEAL" && result ? `결과 ${YUT_LABEL[result.yutResult as YutResult] ?? result.yutResult}` : ""}
        </p>
        {error && <p className="error" role="alert">{error}</p>}
        {phase === "REVEAL" && result ? (
          <div className="stage-result">
            <span className="label">결과</span>
            <strong className="result-mark">{YUT_LABEL[result.yutResult as YutResult] ?? result.yutResult}</strong>
          </div>
        ) : (
          <button type="button" className="btn wood" onClick={() => void throwYut()} disabled={!idle}>
            {buttonLabel}
          </button>
        )}
      </div>
    </section>
  );
}
