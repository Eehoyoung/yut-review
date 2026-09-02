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

