"use client";
import { create } from "zustand";
import { ApiClientError } from "@/lib/api";

/**
 * 재인증(step-up) 요청을 화면 한 곳으로 모은다.
 *
 * 서버는 위험한 조작(요금제, 매장 중지, 계정 관리) 앞에서 최근 2단계 인증을 요구하고, 없으면
 * `STEP_UP_REQUIRED`로 거절한다. 화면은 그 거절을 오류로 보여 주는 대신 코드를 묻고, 통과하면
 * 하던 동작을 그대로 다시 실행한다. 사용자가 무엇을 하려 했는지 잊지 않게 하기 위해서다.
 */
type StepUpPrompt = {
  retry: (() => void) | null;
  ask: (retry: () => void) => void;
  close: () => void;
};

export const useStepUpPrompt = create<StepUpPrompt>((set) => ({
  retry: null,
  ask: (retry) => set({ retry }),
  close: () => set({ retry: null }),
}));

export function isStepUp(error: unknown) {
  return error instanceof ApiClientError && error.code === "STEP_UP_REQUIRED";
}

/** 변경 요청의 `onError`에 그대로 물린다. 재인증이 필요할 때만 창이 열린다. */
export function stepUpIfNeeded(error: unknown, retry: () => void) {
  if (isStepUp(error)) useStepUpPrompt.getState().ask(retry);
}
