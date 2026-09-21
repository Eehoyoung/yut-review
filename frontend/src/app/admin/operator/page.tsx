"use client";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ActivityPager } from "@/features/admin/ActivityTable";
import { STORE_STATUS_LABEL, STORE_STATUS_TONE } from "@/features/admin/labels";
import { ResourceMonitor } from "@/features/admin/ResourceMonitor";
import { Dialog } from "@/features/ui/Dialog";
import { formatBusinessNumber, formatPhone } from "@/features/normalize";
import { api, errorMessage } from "@/lib/api";
import type { ApprovalEvent, OperatorStore, OperatorSummary, PageData, StoreStatus } from "@/types/api";

/**
 * 소담랩스 운영자 전용 매장 심사 큐. SYSTEM_ADMIN이 아니면 서버가 403 OPERATOR_ONLY로 막고,
 * 링크는 /admin 헤더에서 역할을 확인한 뒤에만 나온다.
 *
 * 승인·거부·소유권 이전은 되돌리기 어렵다. 그래서 전부 Dialog에서 한 번 더 확인한다
 * (window.confirm은 쓰지 않는다 — 포커스 트랩과 Esc를 네이티브 <dialog>가 해 준다).
 */

const FILTERS: [string, string][] = [
  ["PENDING_APPROVAL", "승인 대기"],
  ["", "전체"],
  ["REJECTED", "거부"],
];

const ACTION_LABEL: Record<ApprovalEvent["action"], string> = {
  APPROVE: "승인",
  REJECT: "거부",
  REVIEW_AGAIN: "재심사",
  OWNERSHIP_CHANGE: "소유권 이전",
};

type ActionResult = { id: number; status?: StoreStatus; changed?: boolean; ownerEmail?: string };
type Sheet = { kind: "approve" | "reject" | "ownership"; store: OperatorStore } | { kind: "rehash" };

const SHEET_TITLE: Record<Sheet["kind"], string> = {
  approve: "매장을 승인할까요?",
  reject: "승인 거부",
  ownership: "소유권 이전",
  rehash: "전화번호 해시 재계산",
};

/** 감사 로그. 접혀 있을 때는 요청하지 않으려고 부모가 열린 행에서만 렌더한다. */
function ApprovalEvents({ storeId }: { storeId: number }) {
  const q = useQuery({
    queryKey: ["operator-events", storeId],
    queryFn: () => api<ApprovalEvent[]>(`/admin/operator/stores/${storeId}/approval-events`),
  });
  if (q.isPending)
    return (
      <p className="hint" aria-live="polite">
        변경 이력을 불러오는 중
      </p>
    );
  if (q.isError)
    return (
      <p className="error" role="alert">
        {errorMessage(q.error)}
      </p>
    );
  if (q.data.length === 0) return <p className="hint">아직 변경 이력이 없습니다.</p>;
  return (
    <div className="list">
      {q.data.map((e, i) => (
        <div className="list-item" key={`${e.createdAt}-${i}`}>
          <span className="stack" style={{ gap: 2 }}>
            <span className="name">{ACTION_LABEL[e.action] ?? e.action}</span>
            <small className="hint">
              {e.actorEmail}
              {e.note && ` · ${e.note}`}
            </small>
          </span>
          <small className="hint">{new Date(e.createdAt).toLocaleString("ko-KR")}</small>
        </div>
      ))}
    </div>
  );
}

export default function OperatorQueue() {
  const qc = useQueryClient();
  const [status, setStatus] = useState("PENDING_APPROVAL");
  const [page, setPage] = useState(0);
  const [openEvents, setOpenEvents] = useState<number>();
  const [sheet, setSheet] = useState<Sheet>();
  const [value, setValue] = useState("");
  const [tried, setTried] = useState(false);
  const [flash, setFlash] = useState("");

  const summary = useQuery({ queryKey: ["operator-summary"], queryFn: () => api<OperatorSummary>("/admin/operator/summary") });
  const stores = useQuery({
    queryKey: ["operator-stores", status, page],
    queryFn: () =>
      api<PageData<OperatorStore>>(
        `/admin/operator/stores?page=${page}&size=20${status ? `&status=${status}` : ""}`,
      ),
  });

  const closeSheet = () => {
    setSheet(undefined);
    setValue("");
    setTried(false);
  };

  const act = useMutation({
    mutationFn: ({ id, path, body }: { id: number; path: string; body: Record<string, string> }) =>
      api<ActionResult>(`/admin/operator/stores/${id}/${path}`, { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (data, vars) => {
      setFlash(
        vars.path === "approve"
          ? // changed:false는 이미 승인된 매장에 대한 재전송 방어다. 오류가 아니다.
            data.changed === false
            ? "이미 승인된 매장입니다."
            : "승인했습니다."
          : vars.path === "reject"
            ? "승인을 거부했습니다."
            : vars.path === "review-again"
              ? "재심사 대기로 되돌렸습니다."
              : `소유자를 ${data.ownerEmail}로 바꿨습니다.`,
      );
      closeSheet();
      qc.invalidateQueries({ queryKey: ["operator-stores"] });
      qc.invalidateQueries({ queryKey: ["operator-summary"] });
      qc.invalidateQueries({ queryKey: ["operator-events", vars.id] });
    },
  });

  const rehash = useMutation({
    mutationFn: () => api<{ scanned: number; rehashed: number }>("/admin/operator/phone-hash/rehash", { method: "POST" }),
    onSuccess: (d) => {
      setFlash(`${d.scanned}건을 확인해 ${d.rehashed}건을 다시 계산했습니다.`);
      closeSheet();
    },
  });

  // 값을 받아야 하는 동작은 무엇이 비었는지 문장으로 말한다. 버튼을 비활성화하지 않는다.
  const blocked =
    sheet?.kind === "reject" && !value.trim()
      ? "거부 사유를 입력해 주세요."
      : sheet?.kind === "ownership" && !value.trim()
        ? "새 소유자 이메일을 입력해 주세요."
        : "";

  const topError = summary.error ?? stores.error ?? (sheet ? null : act.error);

  const submitSheet = (event: FormEvent) => {
    event.preventDefault();
    if (!sheet) return;
    setTried(true);
    if (sheet.kind === "rehash") {
      rehash.mutate();
      return;
    }
    if (blocked) {
      document.getElementById("sheet-input")?.focus();
      return;
    }
    if (sheet.kind === "approve") act.mutate({ id: sheet.store.id, path: "approve", body: { note: value.trim() } });
    if (sheet.kind === "reject") act.mutate({ id: sheet.store.id, path: "reject", body: { reason: value.trim() } });
    if (sheet.kind === "ownership") act.mutate({ id: sheet.store.id, path: "ownership", body: { email: value.trim() } });
  };

  return (
    <main className="admin-shell">
      <header className="admin-head">
        <div>
          <p className="brand">소담랩스 운영자</p>
          <h1>매장 심사</h1>
        </div>
        <Link className="btn ghost btn-inline" href="/admin">
          내 매장
        </Link>
      </header>

      <dl className="stats" aria-label="심사 현황">
        <div>
          <dt>승인 대기</dt>
          <dd>{summary.data?.pending ?? "-"}</dd>
        </div>
        <div>
          <dt>운영 중</dt>
          <dd>{summary.data?.active ?? "-"}</dd>
        </div>
        <div>
          <dt>거부</dt>
          <dd>{summary.data?.rejected ?? "-"}</dd>
        </div>
      </dl>

      <ResourceMonitor />

      {flash && (
        <p className="success" role="status">
          {flash}
        </p>
      )}
      {/* 시트가 열려 있으면 오류는 시트 안에서만 말한다. 두 번 읽히면 화면낭독기가 겹쳐 읽는다. */}
      {topError && (
        <p className="error" role="alert">
          {errorMessage(topError)}
        </p>
      )}

      <div className="field">
        <label htmlFor="operator-filter">상태</label>
        <select
          id="operator-filter"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          {FILTERS.map(([v, label]) => (
            <option key={label} value={v}>
              {label}
            </option>
          ))}
        </select>
      </div>

      {stores.isPending && (
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">매장 목록을 불러오는 중</span>
          <div className="skeleton" style={{ height: 120 }} />
          <div className="skeleton" style={{ height: 120 }} />
        </div>
      )}

      {stores.data?.content.length === 0 && (
        <section className="panel stack">
          <h2>심사할 매장이 없어요</h2>
          <p className="lead">새 매장이 가입하면 여기에 표시됩니다.</p>
        </section>
      )}

      {stores.data?.content.map((s) => (
        <section className="panel stack" key={s.id}>
          <div className="row">
            <h2>{s.name}</h2>
            <span className="pill" data-tone={STORE_STATUS_TONE[s.status]}>
              {STORE_STATUS_LABEL[s.status]}
            </span>
          </div>
          <div className="list">
            <div className="list-item">
              <span className="lead">사업자등록번호</span>
              <span className="name">{formatBusinessNumber(s.businessNumber)}</span>
            </div>
            <div className="list-item">
              <span className="lead">대표자</span>
              <span className="name">{s.ownerName}</span>
            </div>
            <div className="list-item">
              <span className="lead">이메일</span>
              <span className="name">{s.ownerEmail}</span>
            </div>
            <div className="list-item">
              <span className="lead">연락처</span>
              <span className="name">{formatPhone(s.ownerPhone)}</span>
            </div>
            <div className="list-item">
              <span className="lead">신청</span>
              <span className="name">{new Date(s.createdAt).toLocaleString("ko-KR")}</span>
            </div>
          </div>
          {s.note && <p className="notice">사유: {s.note}</p>}

          <div className="sheet-actions">
            {s.status === "PENDING_APPROVAL" && (
              <button type="button" className="btn btn-inline" onClick={() => setSheet({ kind: "approve", store: s })}>
                승인
              </button>
            )}
            {s.status !== "REJECTED" && (
              <button type="button" className="btn secondary btn-inline" onClick={() => setSheet({ kind: "reject", store: s })}>
                거부
              </button>
            )}
            {s.status === "REJECTED" && (
              <button
                type="button"
                className="btn secondary btn-inline"
                onClick={() => act.mutate({ id: s.id, path: "review-again", body: {} })}
              >
                재심사
              </button>
            )}
            <button type="button" className="btn ghost btn-inline" onClick={() => setSheet({ kind: "ownership", store: s })}>
              소유권 이전
            </button>
          </div>

          {/* 접었다 펴는 것은 브라우저가 이미 한다. 열린 행에서만 이력을 불러온다. */}
          <details onToggle={(e) => setOpenEvents(e.currentTarget.open ? s.id : undefined)}>
            <summary>변경 이력</summary>
            {openEvents === s.id && <ApprovalEvents storeId={s.id} />}
          </details>
        </section>
      ))}

      <ActivityPager page={page} totalPages={stores.data?.totalPages ?? 0} onChange={setPage} />

      <section className="panel stack">
        <h2>위험 작업</h2>
        <p className="lead">전화번호 해시를 새 HMAC 키로 다시 계산합니다. 키 회전 중에만 1회 실행합니다.</p>
        <button type="button" className="btn secondary" onClick={() => setSheet({ kind: "rehash" })}>
          전화번호 해시 재계산
        </button>
      </section>

      <Dialog open={sheet !== undefined} onClose={closeSheet} labelledBy="sheet-title">
        <form className="stack" onSubmit={submitSheet}>
          <h2 id="sheet-title">{sheet ? SHEET_TITLE[sheet.kind] : ""}</h2>

          {sheet && sheet.kind !== "rehash" && (
            <p className="lead">
              {sheet.store.name} · {formatBusinessNumber(sheet.store.businessNumber)}
            </p>
          )}

          {sheet?.kind === "approve" && (
            <>
              <p className="notice">승인하면 QR·포스터·직원 PIN이 즉시 열립니다.</p>
              <div className="field">
                <label htmlFor="sheet-input">메모 (선택)</label>
                <input id="sheet-input" autoFocus maxLength={200} value={value} onChange={(e) => setValue(e.target.value)} />
              </div>
            </>
          )}

          {sheet?.kind === "reject" && (
            <div className="field">
              <label htmlFor="sheet-input">거부 사유</label>
              <textarea
                id="sheet-input"
                autoFocus
                maxLength={200}
                value={value}
                placeholder="사업자등록번호를 확인할 수 없습니다"
                onChange={(e) => setValue(e.target.value)}
              />
              <small className="hint">사장에게 그대로 보입니다. 200자까지.</small>
            </div>
          )}

          {sheet?.kind === "ownership" && (
            <div className="field">
              <label htmlFor="sheet-input">새 소유자 이메일</label>
              <input
                id="sheet-input"
                type="email"
                autoFocus
                inputMode="email"
                value={value}
                onChange={(e) => setValue(e.target.value)}
              />
              <small className="hint">이미 가입된 계정의 이메일이어야 합니다.</small>
            </div>
          )}

          {sheet?.kind === "rehash" && (
            <p className="error">
              HMAC 키 회전 중에만 1회 실행하세요. 이전 키를 지운 뒤에 실행하면 기존 쿨타임 조회가 어긋납니다.
            </p>
          )}

          {tried && blocked && (
            <p className="notice" role="status">
              {blocked}
            </p>
          )}
          {(act.isError || rehash.isError) && (
            <p className="error" role="alert">
              {errorMessage(act.error ?? rehash.error)}
            </p>
          )}

          <div className="sheet-actions">
            <button type="button" className="btn ghost" onClick={closeSheet}>
              취소
            </button>
            <button className="btn" disabled={act.isPending || rehash.isPending}>
              {act.isPending || rehash.isPending ? "처리 중" : "실행"}
            </button>
          </div>
        </form>
      </Dialog>
    </main>
  );
}
