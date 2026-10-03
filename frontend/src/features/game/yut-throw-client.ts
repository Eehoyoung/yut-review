import type { ThrowRecording } from "./yut-throw";

type Response =
  | { type: "ready" }
  | { type: "result"; id: number; recording: ThrowRecording }
  | { type: "error"; id?: number; message: string };

let nextId = 0;
let worker: Worker | undefined;
let ready: Promise<void> | undefined;
const pending = new Map<number, { resolve: (recording: ThrowRecording) => void; reject: (error: Error) => void }>();

function ensureWorker() {
  if (worker && ready) return { worker, ready };

  worker = new Worker(new URL("./yut-throw.worker.ts", import.meta.url), { type: "module" });
  ready = new Promise<void>((resolve, reject) => {
    worker!.onmessage = ({ data }: MessageEvent<Response>) => {
      if (data.type === "ready") return resolve();
      if (data.type === "result") {
        pending.get(data.id)?.resolve(data.recording);
        pending.delete(data.id);
        return;
      }
      const error = new Error(data.message);
      if (data.id === undefined) {
        reject(error);
        pending.forEach(({ reject: rejectPending }) => rejectPending(error));
        pending.clear();
        worker?.terminate();
        worker = undefined;
        ready = undefined;
      } else pending.get(data.id)?.reject(error);
      if (data.id !== undefined) pending.delete(data.id);
    };
    worker!.onerror = () => {
      const error = new Error("physics worker failed");
      reject(error);
      pending.forEach(({ reject: rejectPending }) => rejectPending(error));
      pending.clear();
      worker?.terminate();
      worker = undefined;
      ready = undefined;
    };
  });
  return { worker, ready };
}

export function warmUpPhysics() {
  return ensureWorker().ready;
}

export async function simulateThrow(seed: string, bellies: readonly boolean[]): Promise<ThrowRecording> {
  const physics = ensureWorker();
  await physics.ready;
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    physics.worker.postMessage({ id, seed, bellies });
  });
}
