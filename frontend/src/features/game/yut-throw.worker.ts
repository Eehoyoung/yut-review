/// <reference lib="webworker" />

import { simulateThrow, warmUpPhysics } from "./yut-throw";

type Request = { id: number; seed: string; bellies: readonly boolean[] };

void warmUpPhysics()
  .then(() => self.postMessage({ type: "ready" }))
  .catch((error: unknown) => self.postMessage({ type: "error", message: error instanceof Error ? error.message : "physics init failed" }));

self.onmessage = async ({ data }: MessageEvent<Request>) => {
  try {
    const recording = await simulateThrow(data.seed, data.bellies);
    self.postMessage({ type: "result", id: data.id, recording });
  } catch (error) {
    self.postMessage({ type: "error", id: data.id, message: error instanceof Error ? error.message : "physics simulation failed" });
  }
};
