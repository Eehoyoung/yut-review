import { describe, expect, it } from "vitest";

import { frontFacesFor, type YutResult } from "./yut-result";

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

