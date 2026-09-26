"use client";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ActivityPager } from "@/features/admin/ActivityTable";
import { OperatorFrame } from "@/features/admin/OperatorFrame";
import { Dialog } from "@/features/ui/Dialog";
import { api, errorMessage } from "@/lib/api";
import type { AdminMe, OperatorAdmin, PageData } from "@/types/api";

/**
 * 관리자 계정 목록과 운영자 권한 관리.
 *
 * 운영자 권한은 모든 매장의 승인·거부·소유권 이전을 여는 열쇠라 매장 심사보다 되돌리기 어렵다.
 * 그래서 부여·회수·신설 전부 Dialog에서 한 번 더 확인하고, 서버가 append-only 기록을 남긴다
 * (`operator_audit_events`, 활동 기록 화면에서 본다).
 */

type Sheet =
  | { kind: "grant" | "revoke"; admin: OperatorAdmin }
  | { kind: "create" };

const SHEET_TITLE: Record<Sheet["kind"], string> = {
  grant: "운영자 권한을 줄까요?",
  revoke: "운영자 권한을 회수할까요?",
  create: "운영자 계정 신설",
};

export default function OperatorAccountsPage() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState("");
  /** 입력할 때마다 요청하지 않는다. 검색은 제출한 값으로만 한다. */
  const [submitted, setSubmitted] = useState("");
  const [sheet, setSheet] = useState<Sheet | undefined>();
  const [note, setNote] = useState("");
  const [form, setForm] = useState({ email: "", name: "" });
  const [tried, setTried] = useState(false);
  const [flash, setFlash] = useState("");

  const me = useQuery({ queryKey: ["admin-me"], queryFn: () => api<AdminMe>("/admin/me"), retry: false });

  const admins = useQuery({
    queryKey: ["operator-admins", page, submitted],
    queryFn: () =>
      api<PageData<OperatorAdmin>>(
        `/admin/operator/admins?page=${page}&size=20${submitted ? `&q=${encodeURIComponent(submitted)}` : ""}`,
      ),
  });

  const closeSheet = () => {
    setSheet(undefined);
    setNote("");
    setForm({ email: "", name: "" });
    setTried(false);
  };

  const role = useMutation({
    mutationFn: ({ id, path }: { id: number; path: "grant" | "revoke"; email: string }) =>
      api<OperatorAdmin & { changed: boolean }>(`/admin/operator/admins/${id}/${path}`, {
        method: "POST",
        body: JSON.stringify({ note: note.trim() }),
      }),
    onSuccess: (data, vars) => {
      setFlash(
        // changed:false는 이미 그 상태라는 뜻이다. 재전송 방어이지 오류가 아니다.
        data.changed === false
          ? `${vars.email}은(는) 이미 ${vars.path === "grant" ? "운영자" : "매장 관리자"}입니다.`
          : vars.path === "grant"
            ? `${vars.email}에게 운영자 권한을 줬습니다.`
            : `${vars.email}의 운영자 권한을 회수했습니다.`,
      );
      closeSheet();
      qc.invalidateQueries({ queryKey: ["operator-admins"] });
      qc.invalidateQueries({ queryKey: ["operator-audit"] });
    },
  });

  const create = useMutation({
    mutationFn: () =>
      api<OperatorAdmin>("/admin/operator/admins", {
        method: "POST",
        body: JSON.stringify({ ...form, note: note.trim() }),
      }),
    onSuccess: (data) => {
      setFlash(`운영자 계정 ${data.email}을(를) 만들었습니다.`);
      closeSheet();
      qc.invalidateQueries({ queryKey: ["operator-admins"] });
      qc.invalidateQueries({ queryKey: ["operator-audit"] });
    },
  });

  // 무엇이 비었는지 문장으로 말한다. 버튼을 비활성화하면 왜 못 누르는지가 안 보인다.
  const blocked =
    sheet?.kind !== "create"
      ? ""
      : !form.email.trim()
        ? "이메일을 입력해 주세요."
        : !form.name.trim()
          ? "이름을 입력해 주세요."
          : "";

  const submitSheet = (event: FormEvent) => {
    event.preventDefault();
    if (!sheet) return;
    setTried(true);
    if (sheet.kind === "create") {
      if (blocked) {
        document.getElementById("create-email")?.focus();
        return;
      }
      create.mutate();
      return;
    }
    role.mutate({ id: sheet.admin.id, path: sheet.kind, email: sheet.admin.email });
  };

  const busy = role.isPending || create.isPending;
  const operators = admins.data?.content.filter((a) => a.role === "SYSTEM_ADMIN").length ?? 0;

  return (
    <OperatorFrame title="계정">
      <section className="panel stack">
        <h2>운영자 권한</h2>
        <p className="lead">
          운영자는 모든 매장의 승인·거부·소유권 이전과 요금제 변경을 할 수 있습니다. 매장에는 속하지 않습니다.
        </p>
        <div className="sheet-actions">
          <button type="button" className="btn btn-inline" onClick={() => setSheet({ kind: "create" })}>
            운영자 계정 신설
          </button>
        </div>
      </section>

      <form
        className="field"
        onSubmit={(e) => {
          e.preventDefault();
          setSubmitted(query.trim());
          setPage(0);
        }}
      >
        <label htmlFor="account-query">이메일 또는 이름으로 찾기</label>
        <div className="row">
          <input
            id="account-query"
            value={query}
            placeholder="sodamlabs@example.com"
            onChange={(e) => setQuery(e.target.value)}
          />
          <button className="btn secondary btn-inline">찾기</button>
        </div>
      </form>

      {flash && (
        <p className="success" role="status">
          {flash}
        </p>
      )}
      {/* 시트가 열려 있으면 오류는 시트 안에서만 말한다. 두 번 읽히면 화면낭독기가 겹쳐 읽는다. */}
      {admins.isError && !sheet && (
        <p className="error" role="alert">
          {errorMessage(admins.error)}
        </p>
      )}

      {admins.isPending && (
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">계정 목록을 불러오는 중</span>
          <div className="skeleton" style={{ height: 96 }} />
          <div className="skeleton" style={{ height: 96 }} />
        </div>
      )}

      {admins.data?.content.length === 0 && (
        <section className="panel stack">
          <h2>찾는 계정이 없어요</h2>
          <p className="lead">{submitted ? "검색어를 바꿔 보세요." : "가입한 관리자가 아직 없습니다."}</p>
        </section>
      )}

      {admins.data?.content.map((a) => {
        const isOperator = a.role === "SYSTEM_ADMIN";
        const isMe = me.data?.email === a.email;
        return (
          <section className="panel stack" key={a.id}>
            <div className="row">
              <h2>{a.name}</h2>
              <span className="pill" data-tone={isOperator ? "brand" : "muted"}>
                {isOperator ? "운영자" : "매장 관리자"}
              </span>
            </div>
            <div className="list">
              <div className="list-item">
                <span className="lead">이메일</span>
                <span className="name wrap-anywhere">{a.email}</span>
              </div>
              <div className="list-item">
                <span className="lead">매장</span>
                <span className="name">{a.storeCount}곳</span>
              </div>
              <div className="list-item">
                <span className="lead">가입</span>
                <span className="name">{new Date(a.createdAt).toLocaleString("ko-KR")}</span>
              </div>
            </div>

            {isMe && <p className="notice">내 계정입니다. 자신의 운영자 권한은 회수할 수 없습니다.</p>}

            <div className="sheet-actions">
              {isOperator ? (
                <button
                  type="button"
                  className="btn secondary btn-inline"
                  disabled={isMe}
                  onClick={() => setSheet({ kind: "revoke", admin: a })}
                >
                  운영자 권한 회수
                </button>
              ) : (
                <button type="button" className="btn btn-inline" onClick={() => setSheet({ kind: "grant", admin: a })}>
                  운영자 권한 부여
                </button>
              )}
            </div>
          </section>
        );
      })}

      <ActivityPager page={page} totalPages={admins.data?.totalPages ?? 0} onChange={setPage} />

      <Dialog open={sheet !== undefined} onClose={closeSheet} labelledBy="account-sheet-title">
        <form className="stack" onSubmit={submitSheet}>
          <h2 id="account-sheet-title">{sheet ? SHEET_TITLE[sheet.kind] : ""}</h2>

          {sheet && sheet.kind !== "create" && (
            <p className="lead wrap-anywhere">
              {sheet.admin.name} · {sheet.admin.email}
            </p>
          )}

          {sheet?.kind === "grant" && (
            <p className="notice">
              이 계정이 모든 매장의 승인·거부·소유권 이전과 요금제 변경을 할 수 있게 됩니다. 매장 {sheet.admin.storeCount}곳의
              소유권은 그대로 남습니다.
            </p>
          )}

          {sheet?.kind === "revoke" && (
            <p className="notice">
              심사 화면에 더 이상 들어갈 수 없게 됩니다. 현재 목록에 보이는 운영자는 {operators}명입니다.
            </p>
          )}

          {sheet?.kind === "create" && (
            <>
              <p className="notice">매장은 만들지 않습니다. 이 이메일로 OTP를 받아 로그인하며 비밀번호는 만들지 않습니다.</p>
              <div className="field">
                <label htmlFor="create-email">이메일</label>
                <input
                  id="create-email"
                  type="email"
                  autoFocus
                  inputMode="email"
                  autoComplete="off"
                  value={form.email}
                  onChange={(e) => setForm({ ...form, email: e.target.value })}
                />
              </div>
              <div className="field">
                <label htmlFor="create-name">이름</label>
                <input
                  id="create-name"
                  maxLength={100}
                  autoComplete="off"
                  value={form.name}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                />
              </div>
            </>
          )}

          <div className="field">
            <label htmlFor="account-note">메모 (선택)</label>
            <input id="account-note" maxLength={200} value={note} onChange={(e) => setNote(e.target.value)} />
            <small className="hint">활동 기록에 그대로 남습니다. 왜 했는지 적어 두면 나중에 설명이 필요 없습니다.</small>
          </div>

          {tried && blocked && (
            <p className="notice" role="status">
              {blocked}
            </p>
          )}
          {(role.isError || create.isError) && (
            <p className="error" role="alert">
              {errorMessage(role.error ?? create.error)}
            </p>
          )}

          <div className="sheet-actions">
            <button type="button" className="btn ghost" onClick={closeSheet}>
              취소
            </button>
            <button className="btn" disabled={busy}>
              {busy ? "처리 중" : "실행"}
            </button>
          </div>
        </form>
      </Dialog>
    </OperatorFrame>
  );
}
