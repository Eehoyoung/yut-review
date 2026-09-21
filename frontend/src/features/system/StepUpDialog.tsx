"use client";
import { FormEvent, useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Dialog } from "@/features/ui/Dialog";
import { opsApi } from "@/features/system/api";
import { consoleError } from "@/features/system/labels";
import { useStepUpPrompt } from "@/features/system/stepUp";

const CODE_LENGTH = 6;

/** 재인증 창. {@link useStepUpPrompt}가 열고, 통과하면 멈춰 있던 동작을 이어서 실행한다. */
export function StepUpDialog() {
  const retry = useStepUpPrompt((s) => s.retry);
  const close = useStepUpPrompt((s) => s.close);
  const [code, setCode] = useState("");

  useEffect(() => {
    if (retry) setCode("");
  }, [retry]);

  const verify = useMutation({
    mutationFn: () => opsApi<{ stepUpFresh: boolean }>("/session/step-up", { method: "POST", body: JSON.stringify({ code }) }),
    onSuccess: () => {
      const pending = retry;
      close();
      pending?.();
    },
  });

  return (
    <Dialog open={retry !== null} onClose={close} labelledBy="step-up-title">
      <form
        className="stack"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          verify.mutate();
        }}
      >
        <h2 id="step-up-title">한 번 더 확인합니다</h2>
        <p className="lead">중요한 변경이라 인증 앱의 코드를 다시 확인합니다.</p>
        <div className="field">
          <label htmlFor="step-up-code">인증 코드</label>
          <input
            id="step-up-code"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={CODE_LENGTH}
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, CODE_LENGTH))}
            autoFocus
            required
          />
        </div>
        {verify.isError && (
          <p className="error" role="alert">
            {consoleError(verify.error)}
          </p>
        )}
        <div className="sheet-actions">
          <button type="button" className="btn ghost" onClick={close}>
            취소
          </button>
          <button className="btn" disabled={verify.isPending || code.length !== CODE_LENGTH}>
            {verify.isPending ? "확인 중" : "확인하고 계속"}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
