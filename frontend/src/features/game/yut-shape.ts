/**
 * Traditional 장작윷 proportions: 15~20 cm long, 3~5 cm across, half-moon cross section.
 * The rounded side is the 등 (back), the flat side is the 배 (belly) and is shaped slightly
 * convex so the stick still rolls. A throw counts how many bellies ended up facing the sky.
 *
 * Scene units: 1 unit ~= 8.7 cm, so a stick is 2.3 units long and 0.44 units across.
 */
export const STICK_RADIUS = 0.22;
export const STICK_LENGTH = 2.3;
export const BELLY_BULGE = 0.03;
const SEGMENTS = 14;

/** Cross section in the XY plane: belly faces +Y, rounded back faces -Y. */
export function crossSection(): [number, number][] {
  const points: [number, number][] = [];
  // Rounded back, from the right edge around the bottom to the left edge.
  for (let i = 0; i <= SEGMENTS; i += 1) {
    const angle = Math.PI * (i / SEGMENTS);
    points.push([STICK_RADIUS * Math.cos(angle), -STICK_RADIUS * Math.sin(angle)]);
  }
  // Slightly convex belly back to the right edge.
  for (let i = 1; i < SEGMENTS; i += 1) {
    const t = i / SEGMENTS;
    const x = -STICK_RADIUS + 2 * STICK_RADIUS * t;
    points.push([x, BELLY_BULGE * Math.sin(Math.PI * t)]);
  }
  return points;
}

/** Convex hull vertices (flat Float32Array) for the physics collider. */
export function hullVertices(): Float32Array {
  const section = crossSection();
  const halfLength = STICK_LENGTH / 2;
  const vertices: number[] = [];
  for (const [x, y] of section) {
    vertices.push(x, y, -halfLength, x, y, halfLength);
  }
  return new Float32Array(vertices);
}

/** True when the belly (local +Y) points up for this orientation. */
export function bellyIsUp(quaternion: { x: number; y: number; z: number; w: number }) {
  // Local +Y rotated into world space, y component only.
  return 1 - 2 * (quaternion.x * quaternion.x + quaternion.z * quaternion.z) > 0;
}

/** How firmly the stick lies on a face; near 0 means it is balanced on an edge. */
export function faceConfidence(quaternion: { x: number; y: number; z: number; w: number }) {
  const { x, z } = quaternion;
  return Math.abs(1 - 2 * (x * x + z * z));
}
