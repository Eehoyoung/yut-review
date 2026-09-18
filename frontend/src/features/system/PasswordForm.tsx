"use client";
import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { opsApi } from "@/features/system/api";
import { consoleError } from "@/features/system/labels";

/**
 * 비밀번호 변경. 임시 비밀번호를 받은 계정은 이걸 끝내기 전에는 콘솔에서 아무것도 하지 못한다.
 * 바꾸고 나면 다른 기기의 세션은 서버가 모두 닫는다.
 */
export function PasswordForm({ onDone }: { onDone?: () => void }) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");

  const change = useMutation({
    mutationFn: () =>
      opsApi<{ status: string }>("/account/password", {
        method: "POST",
        body: JSON.stringify({ currentPassword: current, newPassword: next, newPasswordConfirm: confirm }),
      }),
    onSuccess: () => {
      setCurrent("");
      setNext("");
      setConfirm("");
      onDone?.();
    },
  });

  return (
    <form
      className="panel stack"
      onSubmit={(e: FormEvent) => {
        e.preventDefault();
        change.mutate();
      }}
    >
      <h2>비밀번호 변경</h2>
      <p className="lead">영문·숫자·기호를 포함해 12자 이상. 이메일과 겹치는 값은 쓸 수 없습니다.</p>
      <div className="field">
        <label htmlFor="pw-current">현재 비밀번호</label>
        <input
          id="pw-current"
          type="password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="pw-next">새 비밀번호</label>
        <input
          id="pw-next"
          type="password"
          autoComplete="new-password"
          value={next}
          onChange={(e) => setNext(e.target.value)}
          required
        />
      </div>
      <div className="field">
        <label htmlFor="pw-confirm">새 비밀번호 확인</label>
        <input
          id="pw-confirm"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          required
        />
      </div>
      {change.isError && (
        <p className="error" role="alert">
          {consoleError(change.error)}
        </p>
      )}
      {change.isSuccess && (
        <p className="success" role="status">
          비밀번호를 바꿨습니다. 다른 기기의 세션은 모두 종료했습니다.
        </p>
      )}
      <button className="btn" disabled={change.isPending}>
        {change.isPending ? "변경 중" : "비밀번호 변경"}
      </button>
    </form>
  );
}
