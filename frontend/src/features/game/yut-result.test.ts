import { describe, expect, it } from "vitest";

import { frontFacesFor, landingHalfTurns, type YutResult } from "./yut-result";

describe("frontFacesFor", () => {
  it.each<[YutResult, number]>([
    ["DO", 1],
    ["GAE", 2],
    ["GEOL", 3],
    ["YUT", 4],
    ["MO", 0],
  ])("maps %s to %i front faces", (result, count) => {
    expect(frontFacesFor(result).filter(Boolean)).toHaveLength(count);
  });
});

describe("landingHalfTurns", () => {
  const flights = [0.55, 0.62, 0.68, 0.71, 0.78];

  it("ends flat side up for a front face and back side up otherwise", () => {
    for (const flight of flights) {
      for (const extra of [0, 2, 4]) {
        expect(landingHalfTurns(true, flight, extra) % 2).toBe(0);
        expect(landingHalfTurns(false, flight, extra) % 2).toBe(1);
      }
    }
  });

  it("keeps the spin in a natural range so the throw still looks thrown", () => {
    for (const flight of flights) {
      const spin = (landingHalfTurns(true, flight) * Math.PI) / flight;
      expect(spin).toBeGreaterThan(7);
      expect(spin).toBeLessThan(24);
    }
  });

  it("never returns fewer than two half turns", () => {
    expect(landingHalfTurns(true, 0.05)).toBeGreaterThanOrEqual(2);
    expect(landingHalfTurns(false, 0.05)).toBeGreaterThanOrEqual(3);
  });
});
