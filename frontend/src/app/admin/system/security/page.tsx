"use client";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { SystemFrame } from "@/features/system/SystemFrame";
import { PasswordForm } from "@/features/system/PasswordForm";
import { opsApi } from "@/features/system/api";
import { SESSION_END_REASON, consoleError, dateTime } from "@/features/system/labels";
import { isStepUp, stepUpIfNeeded } from "@/features/system/stepUp";
import type { ConsoleSession, OperatorMe } from "@/types/api";

/**
 * 내 계정 보안.
 *
 * 세 가지를 한 화면에 둔다: 지금 열려 있는 세션, 복구 코드, 비밀번호. 사고가 의심될 때 사람이
 * 찾는 것이 이 셋이고, 흩어져 있으면 그 순간에 찾지 못한다.
 */
export default function SecurityPage() {
  const qc = useQueryClient();
  const [issued, setIssued] = useState<string[]>();

  const me = useQuery({ queryKey: ["ops-me"], queryFn: () => opsApi<OperatorMe>("/me"), retry: false });
  const sessions = useQuery({
    queryKey: ["ops-sessions"],
    queryFn: () => opsApi<ConsoleSession[]>("/sessions"),
    retry: false,
  });

  const revoke = useMutation({
    mutationFn: (id: number) => opsApi<{ status: string }>(`/sessions/${id}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["ops-sessions"] }),
    onError: (error, id) => stepUpIfNeeded(error, () => revoke.mutate(id)),
  });

  const regenerate = useMutation({
    mutationFn: () => opsApi<{ backupCodes: string[] }>("/account/backup-codes", { method: "POST" }),
    onSuccess: (data) => {
      setIssued(data.backupCodes);
      qc.invalidateQueries({ queryKey: ["ops-me"] });
    },
    onError: (error) => stepUpIfNeeded(error, () => regenerate.mutate()),
  });

  return (
    <SystemFrame title="보안">
      <section className="panel stack">
        <h2>2단계 인증</h2>
        <div className="list">
          <div className="list-item">
            <span className="lead">복구 코드</span>
            <span className="name">{me.data ? `${me.data.backupCodesRemaining}개 남음` : "-"}</span>
          </div>
          <div className="list-item">
            <span className="lead">재인증 간격</span>
            <span className="name">{me.data ? `${me.data.stepUpMinutes}분` : "-"}</span>
          </div>
          <div className="list-item">
            <span className="lead">세션 최대 시간</span>
            <span className="name">{me.data ? `${me.data.sessionMinutes}분` : "-"}</span>
          </div>
        </div>
        {issued && (
          <>
            <p className="notice" role="status">
              새 복구 코드입니다. 예전 코드는 모두 무효가 됐습니다. <b>이 화면을 닫으면 다시 볼 수 없습니다.</b>
            </p>
            <ul className="backup-codes">
              {issued.map((code) => (
                <li key={code}>{code}</li>
              ))}
            </ul>
          </>
        )}
        {regenerate.isError && !isStepUp(regenerate.error) && (
          <p className="error" role="alert">
            {consoleError(regenerate.error)}
          </p>
        )}
        <button className="btn secondary" disabled={regenerate.isPending} onClick={() => regenerate.mutate()}>
          {regenerate.isPending ? "발급 중" : "복구 코드 새로 발급"}
        </button>
      </section>

      <section className="panel stack">
        <h2>내 세션</h2>
        <p className="lead">모르는 기기가 있으면 지금 종료하세요.</p>
        {sessions.isError && (
          <p className="error" role="alert">
            {consoleError(sessions.error)}
          </p>
        )}
        {sessions.isPending && <div className="skeleton" style={{ height: 120 }} />}
        <div className="list">
          {sessions.data?.map((session) => (
            <div className="list-item" key={session.id}>
              <span className="stack" style={{ gap: 2 }}>
                <span className="name">
                  {session.device || "알 수 없는 기기"}
                  {session.current && " · 지금 이 기기"}
                </span>
                <small className="hint wrap-anywhere">
                  {session.ip} · 최근 활동 {dateTime(session.lastSeenAt)}
                  {session.revokedAt
                    ? ` · 종료됨 (${SESSION_END_REASON[session.revokedReason] ?? session.revokedReason})`
                    : ""}
                </small>
              </span>
              {!session.revokedAt && !session.current && (
                <button
                  className="btn ghost btn-inline"
                  disabled={revoke.isPending}
                  onClick={() => revoke.mutate(session.id)}
                >
                  종료
                </button>
              )}
            </div>
          ))}
        </div>
        {revoke.isError && !isStepUp(revoke.error) && (
          <p className="error" role="alert">
            {consoleError(revoke.error)}
          </p>
        )}
      </section>

      <PasswordForm />
    </SystemFrame>
  );
}
