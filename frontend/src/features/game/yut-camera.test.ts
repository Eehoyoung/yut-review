import { describe, expect, it } from "vitest";

import { yutCameraPose } from "./yut-camera";

describe("yutCameraPose", () => {
  it("widens the vertical field of view on narrow portrait canvases", () => {
    expect(yutCameraPose("AIR", 0.5).fov).toBeGreaterThan(yutCameraPose("AIR", 0.8).fov);
  });

  it("keeps compensation bounded for unusually narrow embedded browsers", () => {
    expect(yutCameraPose("AIR", 0.2).fov).toBeLessThanOrEqual(65);
  });

  it("uses a closer composition before the throw and after settling", () => {
    expect(yutCameraPose("READY", 0.65).position[1]).toBeLessThan(yutCameraPose("AIR", 0.65).position[1]);
    expect(yutCameraPose("REVEAL", 0.65).position[1]).toBeLessThan(yutCameraPose("AIR", 0.65).position[1]);
  });
});
