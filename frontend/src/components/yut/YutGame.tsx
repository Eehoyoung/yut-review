"use client";

import { RoundedBox } from "@react-three/drei";
import { Canvas, useFrame } from "@react-three/fiber";
import { CuboidCollider, Physics, RigidBody, type RapierRigidBody } from "@react-three/rapier";
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Euler, MathUtils, Quaternion, Vector3 } from "three";

import type { RevealResponse } from "@/types/api";
import { frontFacesFor, type YutResult } from "@/features/game/yut-result";

type Phase =
  | "READY"
  | "THROW"
  | "AIR"
  | "IMPACT"
  | "BOUNCE"
  | "ROLL"
  | "SETTLE"
  | "RESULT_LOCK"
  | "REVEAL";

type Props = {
  playId: string;
  animationSeed: string;
  reveal: () => Promise<RevealResponse>;
  onRevealed?: (result: RevealResponse) => void;
  className?: string;
};

function seeded(seed: string) {
  let value = 2166136261;
  for (const char of seed) value = Math.imul(value ^ char.charCodeAt(0), 16777619);
  return () => {
    value += 0x6d2b79f5;
    let next = value;
    next = Math.imul(next ^ (next >>> 15), next | 1);
    next ^= next + Math.imul(next ^ (next >>> 7), next | 61);
    return ((next ^ (next >>> 14)) >>> 0) / 4294967296;
  };
}

function YutStick({
  index,
  phase,
  seed,
  front,
  onImpact,
}: {
  index: number;
  phase: Phase;
  seed: string;
  front?: boolean;
  onImpact: () => void;
}) {
  const body = useRef<RapierRigidBody>(null);
  const correctionStarted = useRef<number | undefined>(undefined);
  const impactPlayed = useRef(false);
  const motion = useMemo(() => {
    const random = seeded(`${seed}:${index}`);
    return {
      position: [(index - 1.5) * 0.48, 1.1 + random() * 0.3, (random() - 0.5) * 0.4] as const,
      impulse: [(random() - 0.5) * 1.8, 4.6 + random(), -1.7 - random()] as const,
      torque: [(random() - 0.5) * 18, (random() - 0.5) * 13, (random() - 0.5) * 16] as const,
      target: new Vector3((index - 1.5) * 0.58, 0.2, (index % 2 ? 0.24 : -0.22)),
      yaw: (random() - 0.5) * 0.42,
    };
  }, [index, seed]);

  useEffect(() => {
    if (phase !== "THROW" || !body.current) return;
    impactPlayed.current = false;
    correctionStarted.current = undefined;
    body.current.setEnabledRotations(true, true, true, true);
    body.current.setTranslation({ x: motion.position[0], y: motion.position[1], z: motion.position[2] }, true);
    body.current.setRotation({ x: 0, y: 0, z: 0, w: 1 }, true);
    body.current.setLinvel({ x: 0, y: 0, z: 0 }, true);
    body.current.setAngvel({ x: 0, y: 0, z: 0 }, true);
    body.current.applyImpulse({ x: motion.impulse[0], y: motion.impulse[1], z: motion.impulse[2] }, true);
    body.current.applyTorqueImpulse({ x: motion.torque[0], y: motion.torque[1], z: motion.torque[2] }, true);
  }, [motion, phase]);

  useFrame(({ clock }) => {
    if (phase !== "RESULT_LOCK" || front === undefined || !body.current) return;
    correctionStarted.current ??= clock.elapsedTime;
    const progress = MathUtils.smoothstep(clock.elapsedTime - correctionStarted.current, 0, 0.48);
    const currentRotation = body.current.rotation();
    const from = new Quaternion(currentRotation.x, currentRotation.y, currentRotation.z, currentRotation.w);
    const target = new Quaternion().setFromEuler(new Euler(0, motion.yaw, front ? 0 : Math.PI));
    body.current.setLinvel({ x: 0, y: 0, z: 0 }, true);
    body.current.setAngvel({ x: 0, y: 0, z: 0 }, true);
    const translation = body.current.translation();
    body.current.setTranslation(
      progress === 1 ? motion.target : new Vector3(translation.x, translation.y, translation.z).lerp(motion.target, progress * 0.18),
      true,
    );
    body.current.setRotation(progress === 1 ? target : from.slerp(target, progress * 0.22), true);
    if (progress === 1) body.current.setEnabledRotations(false, false, false, true);
  });

  return (
    <RigidBody
      ref={body}
      colliders={false}
      position={motion.position}
      restitution={0.34}
      friction={0.86}
      linearDamping={0.34}
      angularDamping={0.48}
      onCollisionEnter={() => {
        if (!impactPlayed.current) {
          impactPlayed.current = true;
          onImpact();
        }
      }}
    >
      <CuboidCollider args={[0.22, 0.1, 1.15]} />
      <RoundedBox args={[0.44, 0.2, 2.3]} radius={0.09} smoothness={3} castShadow>
        <meshStandardMaterial color="#c88b49" roughness={0.7} metalness={0.03} />
      </RoundedBox>
      <mesh position={[0, 0.106, 0]} rotation={[-Math.PI / 2, 0, 0]}>
        <planeGeometry args={[0.24, 1.82]} />
        <meshStandardMaterial color="#f5c879" roughness={0.82} />
      </mesh>
      <mesh position={[0, -0.106, 0]} rotation={[Math.PI / 2, 0, 0]}>
        <planeGeometry args={[0.24, 1.82]} />
        <meshStandardMaterial color="#6f3823" roughness={0.9} />
      </mesh>
    </RigidBody>
  );
}

function Board({ phase, seed, result, onImpact }: { phase: Phase; seed: string; result?: YutResult; onImpact: () => void }) {
  const faces = result ? frontFacesFor(result) : [];
  return (
    <>
      <ambientLight intensity={1.2} />
      <directionalLight position={[3, 7, 4]} intensity={2.4} castShadow shadow-mapSize={[768, 768]} />
      <Physics gravity={[0, -9.81, 0]} timeStep="vary">
        <RigidBody type="fixed" colliders={false}>
          <CuboidCollider args={[4.5, 0.12, 4]} position={[0, -0.12, 0]} />
          <mesh receiveShadow>
            <cylinderGeometry args={[4, 4.4, 0.18, 48]} />
            <meshStandardMaterial color="#173c35" roughness={0.92} />
          </mesh>
        </RigidBody>
        {[0, 1, 2, 3].map((index) => (
          <YutStick key={index} index={index} phase={phase} seed={seed} front={faces[index]} onImpact={onImpact} />
        ))}
      </Physics>
    </>
  );
}

const PHASE_LABEL: Record<Phase, string> = {
  READY: "던질 준비가 됐어요",
  THROW: "힘껏 던졌어요",
  AIR: "윷이 날아갑니다",
  IMPACT: "탁!",
  BOUNCE: "결과를 만드는 중",
  ROLL: "조금만 기다려주세요",
  SETTLE: "결과를 확인하고 있어요",
  RESULT_LOCK: "윷이 멈췄어요",
  REVEAL: "결과가 나왔어요",
};

export default function YutGame({ playId, animationSeed, reveal, onRevealed, className }: Props) {
  const [phase, setPhase] = useState<Phase>("READY");
  const [result, setResult] = useState<RevealResponse>();
  const [error, setError] = useState("");
  const timers = useRef<number[]>([]);
  const impactSoundPlayed = useRef(false);

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
      // Audio is optional; muted/autoplay-restricted devices keep the full visual flow.
    }
  }, []);

  useEffect(() => () => timers.current.forEach(window.clearTimeout), []);

  const schedule = useCallback((next: Phase, delay: number) => {
    timers.current.push(window.setTimeout(() => setPhase(next), delay));
  }, []);

  const revealResult = useCallback(async () => {
    setError("");
    try {
      const revealed = await reveal();
      setResult(revealed);
      setPhase("RESULT_LOCK");
      timers.current.push(window.setTimeout(() => {
        setPhase("REVEAL");
        sound(revealed.yutResult === "MO" ? 660 : 440, 0.18);
        timers.current.push(window.setTimeout(() => onRevealed?.(revealed), 900));
      }, 560));
    } catch {
      setError("결과를 불러오지 못했어요. 같은 게임 결과를 다시 확인해주세요.");
      setPhase("SETTLE");
    }
  }, [onRevealed, reveal, sound]);

  const throwYut = useCallback(() => {
    if (phase !== "READY") return;
    setError("");
    impactSoundPlayed.current = false;
    sound(180, 0.06);
    setPhase("THROW");
    schedule("AIR", 220);
    schedule("IMPACT", 900);
    schedule("BOUNCE", 1220);
    schedule("ROLL", 1780);
    schedule("SETTLE", 2700);
    timers.current.push(window.setTimeout(() => void revealResult(), 2820));
  }, [phase, revealResult, schedule, sound]);

  const onImpact = useCallback(() => {
    if (impactSoundPlayed.current) return;
    impactSoundPlayed.current = true;
    sound(92, 0.12);
  }, [sound]);

  return (
    <section className={className} aria-label={`윷놀이 ${playId}`} style={{ position: "relative", minHeight: 520, overflow: "hidden", borderRadius: 28, background: "radial-gradient(circle at 50% 18%, #37695c 0, #163a34 50%, #0c2421 100%)", color: "#fff8e7", boxShadow: "inset 0 1px rgba(255,255,255,.16), 0 24px 60px rgba(8,30,27,.24)" }}>
      <div aria-hidden style={{ position: "absolute", inset: 0, opacity: 0.14, backgroundImage: "repeating-linear-gradient(112deg, transparent 0 18px, rgba(255,255,255,.08) 19px, transparent 20px)" }} />
      <div style={{ position: "relative", zIndex: 1, padding: "24px 22px 0", textAlign: "center" }}>
        <small style={{ color: "#e9c987", fontWeight: 800, letterSpacing: ".18em" }}>REVIEW YUT</small>
        <h2 aria-live="polite" style={{ margin: "8px 0 0", fontSize: "clamp(1.35rem, 6vw, 2rem)", letterSpacing: "-.04em" }}>{PHASE_LABEL[phase]}</h2>
      </div>
      <div style={{ position: "absolute", inset: "76px 0 72px" }}>
        <Canvas shadows camera={{ position: [0, 5.7, 6.5], fov: 38 }} dpr={[1, 1.5]} gl={{ antialias: false, powerPreference: "high-performance" }}>
          <Suspense fallback={null}>
            <Board phase={phase} seed={animationSeed} result={result?.yutResult as YutResult | undefined} onImpact={onImpact} />
          </Suspense>
        </Canvas>
      </div>
      <div style={{ position: "absolute", zIndex: 2, inset: "auto 22px 20px", textAlign: "center" }}>
        {error && <p role="alert" style={{ margin: "0 0 10px", color: "#ffd2bd", fontSize: 14 }}>{error}</p>}
        {error ? (
          <button type="button" onClick={() => void revealResult()} style={{ width: "100%", minHeight: 52, border: 0, borderRadius: 15, color: "#173229", background: "#f5d68b", fontSize: 17, fontWeight: 900 }}>결과 다시 확인</button>
        ) : phase === "REVEAL" && result ? (
          <strong style={{ display: "block", color: "#ffd889", fontSize: "clamp(2rem, 11vw, 3.4rem)", textShadow: "0 4px 24px rgba(255,206,105,.3)" }}>{result.yutResult}</strong>
        ) : (
          <button type="button" onClick={throwYut} disabled={phase !== "READY"} style={{ width: "100%", minHeight: 52, border: 0, borderRadius: 15, color: "#173229", background: phase === "READY" ? "#f5d68b" : "rgba(255,255,255,.16)", fontSize: 17, fontWeight: 900, cursor: phase === "READY" ? "pointer" : "default" }}>
            {phase === "READY" ? "윷 던지기" : "결과를 기다리는 중"}
          </button>
        )}
      </div>
    </section>
  );
}
