"use client";
import { type FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import type { RetryMode, RetrySettings } from "@/types/api";

/**
 * 한판 더 켜기/끄기. 기본은 꺼짐이다. 어떤 게임에 기회를 줄지는 서버가 게임을 만들 때 정하고,
 * 여기서 바꾼 값은 그 뒤에 생기는 게임부터 적용된다.
 */
export function RetrySettingsPanel({ storeId }: { storeId: string }) {
  const qc = useQueryClient();
  const key = ["retry-settings", storeId];
  const settings = useQuery({ queryKey: key, queryFn: () => api<RetrySettings>(`/admin/stores/${storeId}/retry-settings`) });
  const [draft, setDraft] = useState<{ enabled: boolean; mode: RetryMode; interval: string }>();

  useEffect(() => {
    if (settings.data)
      setDraft({ enabled: settings.data.enabled, mode: settings.data.mode, interval: String(settings.data.interval) });
  }, [settings.data]);

  const save = useMutation({
    mutationFn: (body: { enabled: boolean; mode: RetryMode; interval: number }) =>
      api<RetrySettings>(`/admin/stores/${storeId}/retry-settings`, { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: (d) => qc.setQueryData(key, d),
  });

  if (settings.isError)
    return (
      <p className="error" role="alert">
        {errorMessage(settings.error)}
      </p>
    );
  if (!settings.data || !draft)
    return (
      <div aria-live="polite" aria-busy="true">
        <span className="visually-hidden">한판 더 설정을 불러오는 중</span>
        <div className="skeleton" style={{ height: 160 }} />
      </div>
    );

  const { minInterval, maxInterval } = settings.data;
  const n = Number(draft.interval);
  const invalid = !draft.interval || n < minInterval || n > maxInterval;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!invalid) save.mutate({ enabled: draft.enabled, mode: draft.mode, interval: n });
  };

  return (
    <form className="panel stack" aria-labelledby="retry-title" onSubmit={submit} noValidate>
      <div className="owner-section-heading">
        <h2 id="retry-title">한판 더</h2>
        <p className="hint">
          윷 결과가 나온 뒤 손님에게 &quot;한판 더?&quot;를 물어요. 다시 던지면 첫 결과는 사라지고 새 결과로 쿠폰이 바뀌어요.
        </p>
      </div>

      <label className="row" style={{ justifyContent: "flex-start", gap: 8 }}>
        <input type="checkbox" checked={draft.enabled} onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })} />
        한판 더 사용
      </label>

      {draft.enabled && (
        <>
          <fieldset className="field">
            <legend>기회를 주는 방식</legend>
            <label className="row" style={{ justifyContent: "flex-start", gap: 8 }}>
              <input type="radio" name="retry-mode" checked={draft.mode === "EVERY_N"} onChange={() => setDraft({ ...draft, mode: "EVERY_N" })} />
              정해진 간격 — {draft.interval || "N"}번째 참여마다
            </label>
            <label className="row" style={{ justifyContent: "flex-start", gap: 8 }}>
              <input type="radio" name="retry-mode" checked={draft.mode === "RANDOM"} onChange={() => setDraft({ ...draft, mode: "RANDOM" })} />
              시스템 무작위 — 평균 {draft.interval || "N"}판에 한 번
            </label>
          </fieldset>
          <div className="field">
            <label htmlFor="retry-interval">간격 N (판)</label>
            <input
              id="retry-interval"
              inputMode="numeric"
              autoComplete="off"
              value={draft.interval}
              onChange={(e) => setDraft({ ...draft, interval: e.target.value.replace(/\D/g, "").slice(0, 3) })}
            />
            <small className="hint" style={invalid ? { color: "var(--danger)" } : undefined}>
              {minInterval}에서 {maxInterval} 사이의 숫자
            </small>
          </div>
        </>
      )}

      {save.isError && (
        <p className="error" role="alert">
          {errorMessage(save.error)}
        </p>
      )}
      {save.isSuccess && (
        <p className="success" role="status">
          {save.data.enabled ? "한판 더를 켰어요. 지금부터 참여하는 손님에게 적용돼요." : "한판 더를 껐어요."}
        </p>
      )}
      <button className="btn secondary" disabled={save.isPending || invalid}>
        {save.isPending ? "저장 중..." : "저장"}
      </button>
    </form>
  );
}
