export type YutResult = "DO" | "GAE" | "GEOL" | "YUT" | "MO";

const FRONT_COUNTS: Record<YutResult, number> = {
  DO: 1,
  GAE: 2,
  GEOL: 3,
  YUT: 4,
  MO: 0,
};

export function frontFacesFor(result: YutResult): readonly boolean[] {
  return Array.from({ length: 4 }, (_, index) => index < FRONT_COUNTS[result]);
}

/**
 * Half turns (rotations of PI about the stick's transverse axis) the throw must complete so the
 * stick is already showing the wanted face at touchdown. Even = flat face up, odd = back up.
 * Keeping this pure is what lets the "landed shape equals the server result" rule be tested.
 */
export function landingHalfTurns(front: boolean, flightSeconds: number, extraHalfTurns = 0, targetSpin = 13) {
  let halfTurns = Math.max(2, Math.round((targetSpin * flightSeconds) / Math.PI)) + extraHalfTurns;
  if (halfTurns % 2 !== (front ? 0 : 1)) halfTurns += 1;
  return halfTurns;
}

