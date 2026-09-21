"use client";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { SystemFrame } from "@/features/system/SystemFrame";
import { Dialog } from "@/features/ui/Dialog";
import { opsApi } from "@/features/system/api";
import { ROLE_DESCRIPTION, ROLE_LABEL, consoleError, dateTime } from "@/features/system/labels";
import { isStepUp, stepUpIfNeeded } from "@/features/system/stepUp";
import type { ConsoleOperator, ConsoleRole } from "@/types/api";

const ROLES: ConsoleRole[] = ["VIEWER", "OPERATOR", "OWNER"];
const EMPTY = { email: "", name: "", password: "", consoleRole: "VIEWER" as ConsoleRole };

/**
 * 운영자 계정 관리(총괄 권한 전용).
 *
 * 콘솔 밖에서 운영자를 늘리지 않아도 되게 한다. DB를 직접 고치거나 환경변수로 계정을 만들면
 * 누가 언제 누구에게 무슨 권한을 줬는지가 아무 데도 남지 않는다.
 */
export default function Operators() {
  const qc = useQueryClient();
  const [form, setForm] = useState(EMPTY);
  const [adding, setAdding] = useState(false);
  const [openId, setOpenId] = useState<number>();

  const list = useQuery({
    queryKey: ["ops-operators"],
    queryFn: () => opsApi<ConsoleOperator[]>("/operators"),
    retry: false,
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ["ops-operators"] });
  const selected = list.data?.find((o) => o.id === openId);

  const create = useMutation({
    mutationFn: () => opsApi<{ id: number }>("/operators", { method: "POST", body: JSON.stringify(form) }),
    onSuccess: () => {
      setForm(EMPTY);
      setAdding(false);
      refresh();
    },
    onError: (error) => stepUpIfNeeded(error, () => create.mutate()),
  });

  const update = useMutation({
    mutationFn: (body: { id: number; consoleRole?: ConsoleRole; disabled?: boolean; allowedIps?: string }) =>
      opsApi<ConsoleOperator>(`/operators/${body.id}`, {
        method: "PUT",
        body: JSON.stringify({ consoleRole: body.consoleRole, disabled: body.disabled, allowedIps: body.allowedIps }),
      }),
    onSuccess: refresh,
    onError: (error, body) => stepUpIfNeeded(error, () => update.mutate(body)),
  });

  const resetTotp = useMutation({
    mutationFn: (id: number) => opsApi<{ status: string }>(`/operators/${id}/totp/reset`, { method: "POST" }),
    onSuccess: refresh,
    onError: (error, id) => stepUpIfNeeded(error, () => resetTotp.mutate(id)),
  });

  const unlock = useMutation({
    mutationFn: (id: number) => opsApi<{ status: string }>(`/operators/${id}/unlock`, { method: "POST" }),
    onSuccess: refresh,
    onError: (error, id) => stepUpIfNeeded(error, () => unlock.mutate(id)),
  });

  const failed = [create, update, resetTotp, unlock].find((m) => m.isError && !isStepUp(m.error));

  return (
    <SystemFrame title="운영자">
      <section className="row">
        <p className="lead">콘솔에 들어올 수 있는 계정</p>
        {!adding && (
          <button className="btn secondary btn-inline" onClick={() => setAdding(true)}>
            운영자 추가
          </button>
        )}
      </section>

      {list.isError && (
        <p className="error" role="alert">
          {consoleError(list.error)}
        </p>
      )}
      {list.isPending && <div className="skeleton" style={{ height: 160 }} />}

      <div className="list">
        {list.data?.map((operator) => (
          <button className="list-item store-link" key={operator.id} onClick={() => setOpenId(operator.id)}>
            <span className="stack" style={{ gap: 2 }}>
              <span className="name wrap-anywhere">{operator.email}</span>
              <small className="hint">
                {ROLE_LABEL[operator.consoleRole]} · {operator.totpEnrolled ? "2단계 인증 등록됨" : "2단계 인증 미등록"}
                {operator.lastLoginAt ? ` · 마지막 로그인 ${dateTime(operator.lastLoginAt)}` : " · 로그인 기록 없음"}
              </small>
            </span>
            <span className="pill" data-tone={operator.disabled || operator.locked ? "off" : "ok"}>
              {operator.disabled ? "중지" : operator.locked ? "잠김" : "사용 중"}
            </span>
          </button>
        ))}
      </div>

      {failed && (
        <p className="error" role="alert">
          {consoleError(failed.error)}
        </p>
      )}

      {adding && (
        <form
          className="panel stack"
          onSubmit={(e: FormEvent) => {
            e.preventDefault();
            create.mutate();
          }}
        >
          <h2>운영자 추가</h2>
          <p className="lead">
            임시 비밀번호로 만들어집니다. 본인이 첫 로그인에서 비밀번호를 바꾸기 전에는 조회조차 할 수 없습니다.
          </p>
          <div className="field">
            <label htmlFor="new-email">이메일</label>
            <input
              id="new-email"
              type="email"
              value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="new-name">이름</label>
            <input
              id="new-name"
              maxLength={50}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="new-password">임시 비밀번호</label>
            <input
              id="new-password"
              type="password"
              autoComplete="new-password"
              value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
              required
            />
            <small className="hint">영문·숫자·기호를 포함해 12자 이상</small>
          </div>
          <div className="field">
            <label htmlFor="new-role">권한</label>
            <select
              id="new-role"
              value={form.consoleRole}
              onChange={(e) => setForm({ ...form, consoleRole: e.target.value as ConsoleRole })}
            >
              {ROLES.map((role) => (
                <option key={role} value={role}>
                  {ROLE_LABEL[role]} — {ROLE_DESCRIPTION[role]}
                </option>
              ))}
            </select>
          </div>
          <div className="sheet-actions">
            <button
              type="button"
              className="btn ghost"
              onClick={() => {
                setAdding(false);
                setForm(EMPTY);
              }}
            >
              취소
            </button>
            <button className="btn" disabled={create.isPending}>
              {create.isPending ? "추가 중" : "추가"}
            </button>
          </div>
        </form>
      )}

      <Dialog open={openId !== undefined} onClose={() => setOpenId(undefined)} labelledBy="operator-title">
        <div className="stack">
          <h2 id="operator-title" className="wrap-anywhere">
            {selected?.email ?? "운영자"}
          </h2>
          {selected && (
            <>
              <div className="list">
                <div className="list-item">
                  <span className="lead">이름</span>
                  <span className="name">{selected.name}</span>
                </div>
                <div className="list-item">
                  <span className="lead">마지막 로그인</span>
                  <span className="name wrap-anywhere">
                    {dateTime(selected.lastLoginAt)}
                    {selected.lastLoginIp && ` · ${selected.lastLoginIp}`}
                  </span>
                </div>
                <div className="list-item">
                  <span className="lead">임시 비밀번호</span>
                  <span className="name">{selected.mustChangePassword ? "변경 전" : "변경함"}</span>
                </div>
              </div>

              <h3>권한</h3>
              <div className="preset-row">
                {ROLES.map((role) => (
                  <button
                    key={role}
                    className={`btn secondary${selected.consoleRole === role ? " is-on" : ""}`}
                    disabled={update.isPending || selected.consoleRole === role || (selected.self && role !== "OWNER")}
                    onClick={() => update.mutate({ id: selected.id, consoleRole: role })}
                  >
                    {ROLE_LABEL[role]}
                  </button>
                ))}
              </div>
              <p className="hint">{ROLE_DESCRIPTION[selected.consoleRole]}</p>

              <h3>접속 허용 IP</h3>
              <IpRuleField
                key={selected.id}
                value={selected.allowedIps}
                pending={update.isPending}
                onSave={(allowedIps) => update.mutate({ id: selected.id, allowedIps })}
              />

              <h3>계정</h3>
              {!selected.self && (
                <button
                  className="btn secondary"
                  disabled={update.isPending}
                  onClick={() => update.mutate({ id: selected.id, disabled: !selected.disabled })}
                >
                  {selected.disabled ? "사용 재개" : "사용 중지"}
                </button>
              )}
              {selected.locked && (
                <button className="btn secondary" disabled={unlock.isPending} onClick={() => unlock.mutate(selected.id)}>
                  잠금 해제
                </button>
              )}
              <button className="btn ghost" disabled={resetTotp.isPending} onClick={() => resetTotp.mutate(selected.id)}>
                2단계 인증 초기화
              </button>
              <p className="hint">
                초기화하면 그 계정의 세션이 모두 닫히고, 다음 로그인에서 인증 앱을 새로 등록합니다.
              </p>
            </>
          )}
          <div className="sheet-actions">
            <button className="btn ghost" onClick={() => setOpenId(undefined)}>
              닫기
            </button>
          </div>
        </div>
      </Dialog>
    </SystemFrame>
  );
}

/** 계정별 접속 IP. 입력 중 값은 화면에만 두고, 저장은 눌렀을 때만 한다. */
function IpRuleField({ value, pending, onSave }: { value: string; pending: boolean; onSave: (value: string) => void }) {
  const [draft, setDraft] = useState(value);
  return (
    <div className="stack">
      <div className="field">
        <label htmlFor="allowed-ips">IP 또는 CIDR (쉼표로 구분)</label>
        <input
          id="allowed-ips"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          placeholder="비우면 제한 없음"
          maxLength={500}
        />
        <small className="hint">예: 203.0.113.4, 10.0.0.0/8</small>
      </div>
      <button className="btn secondary" disabled={pending || draft === value} onClick={() => onSave(draft)}>
        접속 IP 저장
      </button>
    </div>
  );
}
