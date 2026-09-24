export type YutCameraPhase =
  | "READY"
  | "THROW"
  | "AIR"
  | "IMPACT"
  | "ROLL"
  | "SETTLE"
  | "RESULT_LOCK"
  | "REVEAL";

export type YutCameraPose = {
  position: readonly [number, number, number];
  target: readonly [number, number, number];
  fov: number;
};

const clamp = (value: number, minimum: number, maximum: number) =>
  Math.min(maximum, Math.max(minimum, value));

/**
 * Keeps the verified landing envelope visible on narrow phones while using the otherwise empty
 * vertical space on tall screens. Only the camera moves; landed sticks are never corrected.
 */
export function yutCameraPose(phase: YutCameraPhase, aspect: number): YutCameraPose {
  const safeAspect = clamp(aspect || 0.66, 0.48, 1.25);
  const narrowCompensation = clamp((0.72 - safeAspect) * 30, 0, 7);

  if (phase === "READY") {
    return {
      position: [0, 8.45, 4.72],
      target: [0, 0, 0.45],
      fov: 48 + narrowCompensation,
    };
  }

  if (phase === "SETTLE" || phase === "RESULT_LOCK" || phase === "REVEAL") {
    return {
      position: [0, 10.2, 5.6],
      target: [0, 0, 0.12],
      fov: 57 + narrowCompensation,
    };
  }

  return {
    position: [0, 10.6, 6.2],
    target: [0, 0.35, 0.25],
    fov: 58 + narrowCompensation,
  };
}
