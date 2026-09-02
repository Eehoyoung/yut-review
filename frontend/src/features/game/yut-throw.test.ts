import { describe, expect, it } from "vitest";

import { frontFacesFor, type YutResult } from "./yut-result";
import { bellyIsUp, faceConfidence } from "./yut-shape";
import { landingHalfTurns, simulateThrow, spreadFaces } from "./yut-throw";

const RESULTS: YutResult[] = ["DO", "GAE", "GEOL", "YUT", "MO"];

describe("landingHalfTurns", () => {
  it("ends belly up for a front face and back up otherwise", () => {
    for (const flight of [0.55, 0.62, 0.68, 0.71, 0.78]) {
      for (const bias of [0, 1, 2, 3]) {
        expect(landingHalfTurns(true, flight, bias) % 2).toBe(0);
        expect(landingHalfTurns(false, flight, bias) % 2).toBe(1);
      }
    }
  });
});

describe("simulateThrow", () => {
  it.each(RESULTS)("records a throw that comes to rest showing %s", async (result) => {
    const bellies = frontFacesFor(result);
    const throwRecording = await simulateThrow(`seed-${result}`, bellies);
    const resting = throwRecording.frames[throwRecording.frames.length - 1];

    expect(resting).toHaveLength(4);
    resting.forEach((stick, index) => {
      const quaternion = { x: stick.q[0], y: stick.q[1], z: stick.q[2], w: stick.q[3] };
      // The recorded resting pose is the result: same faces, lying flat, on the mat.
      expect(bellyIsUp(quaternion)).toBe(bellies[index]);
      expect(faceConfidence(quaternion)).toBeGreaterThan(0.7);
      expect(Math.abs(stick.p[0])).toBeLessThan(4.6);
      expect(Math.abs(stick.p[2])).toBeLessThan(4.2);
    });
  }, 30000);

  it("moves every stick on its own path", async () => {
    const recording = await simulateThrow("independent", frontFacesFor("GAE"));
    const resting = recording.frames[recording.frames.length - 1];
    const spread = new Set(resting.map((stick) => stick.p.map((v) => v.toFixed(2)).join(",")));
    expect(spread.size).toBe(4);
    expect(recording.impactStep).toBeGreaterThan(0);
  }, 30000);
});

describe("spreadFaces", () => {
  it("keeps the belly count while varying which sticks show it", () => {
    const bellies = frontFacesFor("DO");
    const seen = new Set<string>();
    for (const seed of ["a", "b", "c", "d", "e", "f", "g", "h"]) {
      const spread = spreadFaces(seed, bellies);
      expect(spread.filter(Boolean)).toHaveLength(1);
      seen.add(spread.join(","));
    }
    expect(seen.size).toBeGreaterThan(1);
  });
});
