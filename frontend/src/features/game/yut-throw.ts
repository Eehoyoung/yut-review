import RAPIER from "@dimforge/rapier3d-compat";

import { bellyIsUp, faceConfidence, hullVertices, STICK_RADIUS } from "./yut-shape";

/**
 * Each stick is thrown and simulated to rest BEFORE anything is drawn, in its own world, and a
 * run is only kept when the stick comes to rest on the face the server already decided. The
 * screen then replays those recordings frame by frame.
 *
 * Consequences, which are the whole point:
 * - the face that lands is the face that stays; nothing is rotated after the throw,
 * - the stick stays exactly where it stopped; nothing is repositioned,
 * - the four sticks are simulated independently, so they never move as a block.
 */
export type StickFrame = {
  p: readonly [number, number, number];
  q: readonly [number, number, number, number];
};
export type ThrowRecording = {
  /** frames[step][stick] at 60 steps per second; the last frame is the resting pose. */
  frames: StickFrame[][];
  /** Step at which the first stick hits the mat, for the impact sound. */
  impactStep: number;
  attempts: number;
};

export const STEP_HZ = 60;
export const LANE_SPACING = 1.15;
const GRAVITY = 30;
const MAX_STEPS = 260;
const REST_STEPS = 12;
const REST_LINEAR = 0.06;
const REST_ANGULAR = 0.12;
const FLAT_CONFIDENCE = 0.72;
const MAX_ATTEMPTS = 24;
const MAT_HALF = 4;
const TARGET_SPIN = 13;
const RESTITUTION = 0.05;
const FRICTION = 1.2;

let ready: Promise<void> | undefined;
function initRapier() {
  ready ??= RAPIER.init();
  return ready;
}

/** Call on mount so WASM fetch+compile overlaps the customer reading the screen, not the throw tap. */
export function warmUpPhysics() {
  return initRapier();
}

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

/**
 * Half turns (rotations of PI about the stick's transverse axis) aimed at the wanted face.
 * Even keeps the belly up, odd lands the stick on its rounded back. The bounce still has a say,
 * which is why every throw is verified afterwards rather than trusted.
 */
export function landingHalfTurns(bellyUp: boolean, flightSeconds: number, bias = 0, targetSpin = TARGET_SPIN) {
  let halfTurns = Math.max(2, Math.round((targetSpin * flightSeconds) / Math.PI)) + bias;
  if (halfTurns % 2 !== (bellyUp ? 0 : 1)) halfTurns += 1;
  return halfTurns;
}

/** Same number of bellies, different sticks each game, so 도 is not always the leftmost stick. */
export function spreadFaces(seed: string, bellies: readonly boolean[]): boolean[] {
  const offset = Math.floor(seeded(`${seed}:faces`)() * bellies.length);
  return bellies.map((_, index) => bellies[(index + offset) % bellies.length]);
}

type Launch = {
  position: [number, number, number];
  velocity: [number, number, number];
  spin: [number, number, number];
};

function launchFor(seed: string, index: number, bellyUp: boolean, attempt: number): Launch {
  const random = seeded(`${seed}:${index}:${attempt}`);
  const lane = (index - 1.5) * LANE_SPACING;
  const x = lane + (random() - 0.5) * 0.12;
  const y = 0.95 + random() * 0.18;
  const z = 1.45 + (random() - 0.5) * 0.4;
  const vy = 9.3 + random() * 1.7;
  const vx = (random() - 0.5) * 0.5;
  const vz = -3.0 - random() * 0.9;
  const flight = (vy + Math.sqrt(vy * vy + 2 * GRAVITY * (y - STICK_RADIUS))) / GRAVITY;
  const halfTurns = landingHalfTurns(bellyUp, flight, Math.floor(random() * 3) * 2 + attempt);
  return {
    position: [x, y, z],
    velocity: [vx, vy, vz],
    // Yut sticks tumble end over end: spin lives on X, with a little yaw on Y and none on the
    // long Z axis, which would look like a boomerang and change the face on its own.
    spin: [(halfTurns * Math.PI) / flight, (random() - 0.5) * 1.2, 0],
  };
}

type Attempt = {
  frames: StickFrame[][];
  impactStep: number;
  bellyUp: boolean;
  flat: boolean;
};

/** One stick, one world: no other stick can nudge it, and the search stays cheap. */
function simulateStick(launch: Launch): Attempt {
  const world = new RAPIER.World({ x: 0, y: -GRAVITY, z: 0 });
  world.timestep = 1 / STEP_HZ;

  const mat = world.createRigidBody(RAPIER.RigidBodyDesc.fixed());
  world.createCollider(
    RAPIER.ColliderDesc.cuboid(MAT_HALF, 0.3, MAT_HALF).setTranslation(0, -0.3, 0).setFriction(FRICTION).setRestitution(RESTITUTION),
    mat,
  );
  // A lane of its own keeps the stick inside its slice of the mat, so replayed sticks can never
  // end up overlapping each other even though they were simulated separately.
  const lane = launch.position[0];
  for (const side of [-1, 1]) {
    world.createCollider(
      RAPIER.ColliderDesc.cuboid(0.1, 1.4, MAT_HALF).setTranslation(lane + side * (LANE_SPACING / 2), 1.4, 0).setRestitution(0.02).setFriction(0.8),
      mat,
    );
  }
  world.createCollider(RAPIER.ColliderDesc.cuboid(MAT_HALF, 1.4, 0.1).setTranslation(0, 1.4, -MAT_HALF + 0.6).setRestitution(0.02), mat);
  world.createCollider(RAPIER.ColliderDesc.cuboid(MAT_HALF, 1.4, 0.1).setTranslation(0, 1.4, MAT_HALF - 0.4).setRestitution(0.02), mat);

  const body = world.createRigidBody(
    RAPIER.RigidBodyDesc.dynamic()
      .setTranslation(launch.position[0], launch.position[1], launch.position[2])
      .setLinvel(launch.velocity[0], launch.velocity[1], launch.velocity[2])
      .setAngvel({ x: launch.spin[0], y: launch.spin[1], z: launch.spin[2] })
      .setLinearDamping(0.2)
      .setAngularDamping(0.45)
      .setCcdEnabled(true),
  );
  const hull = RAPIER.ColliderDesc.convexHull(hullVertices());
  if (!hull) throw new Error("yut collider hull failed");
  world.createCollider(hull.setRestitution(RESTITUTION).setFriction(FRICTION).setMass(0.18), body);

  const frames: StickFrame[][] = [];
  let landed = false;
  let impactStep = 0;
  let calm = 0;

  for (let step = 0; step < MAX_STEPS; step += 1) {
    world.step();
    const t = body.translation();
    const r = body.rotation();
    frames.push([{ p: [t.x, t.y, t.z] as const, q: [r.x, r.y, r.z, r.w] as const }]);

    if (!landed && t.y <= STICK_RADIUS + 0.05) {
      landed = true;
      impactStep = step;
      // Straw-mat energy loss. Without it the stick tumbles on past the face it landed on.
      const spin = body.angvel();
      const velocity = body.linvel();
      body.setAngvel({ x: spin.x * 0.08, y: spin.y * 0.3, z: spin.z * 0.08 }, true);
      body.setLinvel({ x: velocity.x * 0.35, y: velocity.y * 0.35, z: velocity.z * 0.35 }, true);
    }

    if (landed) {
      const v = body.linvel();
      const w = body.angvel();
      const atRest =
        body.isSleeping() ||
        (Math.hypot(v.x, v.y, v.z) < REST_LINEAR && Math.hypot(w.x, w.y, w.z) < REST_ANGULAR);
      calm = atRest ? calm + 1 : 0;
      if (calm >= REST_STEPS) break;
    }
  }

  const rotation = body.rotation();
  const attempt: Attempt = {
    frames,
    impactStep,
    bellyUp: bellyIsUp(rotation),
    flat: faceConfidence(rotation) >= FLAT_CONFIDENCE,
  };
  world.free();
  return attempt;
}

/** Throws one stick over and over until it comes to rest on the face the server decided. */
function throwUntilFace(seed: string, index: number, bellyUp: boolean) {
  for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt += 1) {
    const result = simulateStick(launchFor(seed, index, bellyUp, attempt));
    if (result.bellyUp === bellyUp && result.flat) return { ...result, attempts: attempt + 1 };
  }
  throw new Error(`yut stick ${index} never settled belly ${bellyUp ? "up" : "down"}`);
}

// ponytail: 4 sticks x up to 24 attempts x 260 steps run synchronously on the main thread. Typical
// runs settle in one or two attempts and finish in a few ms; the worst case can block a frame or two
// on a weak phone. Move this into a Web Worker only if a real device shows the freeze.
export async function simulateThrow(seed: string, bellies: readonly boolean[]): Promise<ThrowRecording> {
  await initRapier();
  const sticks = bellies.map((bellyUp, index) => throwUntilFace(seed, index, bellyUp));
  const steps = Math.max(...sticks.map((stick) => stick.frames.length));
  const frames: StickFrame[][] = [];
  for (let step = 0; step < steps; step += 1) {
    frames.push(sticks.map((stick) => stick.frames[Math.min(step, stick.frames.length - 1)][0]));
  }
  return {
    frames,
    impactStep: Math.min(...sticks.map((stick) => stick.impactStep)),
    attempts: sticks.reduce((total, stick) => total + stick.attempts, 0),
  };
}
