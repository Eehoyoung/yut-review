"use client";

import { Canvas, useFrame } from "@react-three/fiber";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { BufferAttribute, Color, ExtrudeGeometry, Quaternion, Shape, type Mesh } from "three";

import type { RevealResponse } from "@/types/api";
import { frontFacesFor, type YutResult } from "@/features/game/yut-result";
import { crossSection, STICK_LENGTH, STICK_RADIUS } from "@/features/game/yut-shape";
import { LANE_SPACING, simulateThrow, spreadFaces, STEP_HZ, type ThrowRecording } from "@/features/game/yut-throw";
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
  onImpact,
  onSettled,
  meshes,
}: {
  recording?: ThrowRecording;
  shadows: boolean;
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

function Mat({ shadows }: { shadows: boolean }) {
  return (
    <mesh position={[0, -0.09, 0]} receiveShadow={shadows}>
      <cylinderGeometry args={[4.2, 4.5, 0.18, 48]} />
      <meshStandardMaterial color="#173c35" roughness={0.94} />
    </mesh>
  );
}

const PHASE_LABEL: Record<Phase, string> = {
  READY: "던질 준비가 됐어요",
  THROW: "힘껏 던졌어요",
  AIR: "윷이 날아갑니다",
  IMPACT: "탁!",
  ROLL: "구르는 중",
  SETTLE: "윷이 멈춰갑니다",
  RESULT_LOCK: "윷이 멈췄어요",
  REVEAL: "결과가 나왔어요",
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
    try {
      const revealed = await reveal();
      const faces = spreadFaces(animationSeed, frontFacesFor(revealed.yutResult as YutResult));
      const throwRecording = await simulateThrow(`${animationSeed}:${revealed.playId}`, faces);
      setResult(revealed);
      setRecording(throwRecording);
      sound(180, 0.06);
      setPhase("THROW");
      schedule("AIR", 160);
    } catch {
      setError("결과를 불러오지 못했어요. 잠시 후 다시 던져주세요.");
    } finally {
      setPreparing(false);
    }
  }, [animationSeed, phase, preparing, reveal, schedule, sound]);

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
  const buttonLabel = preparing ? "윷을 고르는 중..." : error ? "다시 던지기" : phase === "READY" ? "윷 던지기" : "결과를 기다리는 중";

  return (
    <section
      className={className}
      aria-label={`윷놀이 ${playId}`}
      style={{ position: "relative", minHeight: 520, overflow: "hidden", borderRadius: 28, background: "radial-gradient(circle at 50% 18%, #37695c 0, #163a34 50%, #0c2421 100%)", color: "#fff8e7", boxShadow: "inset 0 1px rgba(255,255,255,.16), 0 24px 60px rgba(8,30,27,.24)" }}
    >
      <div aria-hidden style={{ position: "absolute", inset: 0, opacity: 0.14, backgroundImage: "repeating-linear-gradient(112deg, transparent 0 18px, rgba(255,255,255,.08) 19px, transparent 20px)" }} />
      <div style={{ position: "relative", zIndex: 1, padding: "24px 22px 0", textAlign: "center" }}>
        <small style={{ color: "#e9c987", fontWeight: 800, letterSpacing: ".18em" }}>REVIEW YUT</small>
        <h2 aria-live="polite" style={{ margin: "8px 0 0", fontSize: "clamp(1.35rem, 6vw, 2rem)", letterSpacing: "-.04em" }}>{PHASE_LABEL[phase]}</h2>
      </div>
      <div style={{ position: "absolute", inset: "76px 0 72px" }}>
        <Canvas shadows={shadows} camera={{ position: [0, 5.4, 6.2], fov: 40 }} dpr={[1, 1.5]} gl={{ antialias: false, powerPreference: "high-performance" }}>
          <Suspense fallback={null}>
            <ambientLight intensity={1.2} />
            <directionalLight position={[3, 7, 4]} intensity={2.3} castShadow={shadows} shadow-mapSize={[512, 512]} />
            <Mat shadows={shadows} />
            <Sticks recording={recording} shadows={shadows} onImpact={onImpact} onSettled={onSettled} meshes={meshes} />
          </Suspense>
        </Canvas>
      </div>
      <div style={{ position: "absolute", zIndex: 2, inset: "auto 22px 20px", textAlign: "center" }}>
        {error && <p role="alert" style={{ margin: "0 0 10px", color: "#ffd2bd", fontSize: 14 }}>{error}</p>}
        {phase === "REVEAL" && result ? (
          <strong style={{ display: "block", color: "#ffd889", fontSize: "clamp(2rem, 11vw, 3.4rem)", textShadow: "0 4px 24px rgba(255,206,105,.3)" }}>
            {YUT_LABEL[result.yutResult as YutResult] ?? result.yutResult}
          </strong>
        ) : (
          <button
            type="button"
            onClick={() => void throwYut()}
            disabled={!idle}
            style={{ width: "100%", minHeight: 52, border: 0, borderRadius: 15, color: "#173229", background: idle ? "#f5d68b" : "rgba(255,255,255,.16)", fontSize: 17, fontWeight: 900, cursor: idle ? "pointer" : "default" }}
          >
            {buttonLabel}
          </button>
        )}
      </div>
    </section>
  );
}
