"use client";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ActivityPager } from "@/features/admin/ActivityTable";
import {
  PLAN_LABEL,
  SERVICE_STATE_LABEL,
  SERVICE_STATE_TONE,
  STORE_STATUS_LABEL,
  STORE_STATUS_TONE,
} from "@/features/admin/labels";
import { OperatorFrame } from "@/features/operator/OperatorFrame";
import { QrRegenerateDialog } from "@/features/operator/QrRegenerateDialog";
import { Dialog } from "@/features/ui/Dialog";
import { formatBusinessNumber, formatPhone } from "@/features/normalize";
import { api, errorMessage } from "@/lib/api";
import type { ApprovalEvent, OperatorStore, OperatorSummary, PageData, StoreStatus } from "@/types/api";

/**
 * 소담랩스 운영자 전용 매장 목록. 가입 정보, 매장별 게임·쿠폰 숫자, 요금제와 심사 동작을 한 카드에 둔다.
 * 운영자(OPERATOR)가 아니면 서버가 막는다(운영자 OTP 세션 필터 401, 역할 검사 403 OPERATOR_ONLY).
 *
 * 가입이 곧바로 운영 중이 되는지(승인제 여부)는 서버 설정이다. 화면은 `summary.approvalRequired`를 따를 뿐
 * 따로 적지 않는다. 승인제를 꺼 둬도 예전에 대기·거부된 매장과 운영 중단은 여기서 다룬다.
 *
 * 승인·거부·소유권 이전은 되돌리기 어렵다. 그래서 전부 Dialog에서 한 번 더 확인한다
 * (window.confirm은 쓰지 않는다 — 포커스 트랩과 Esc를 네이티브 <dialog>가 해 준다).
 */

const FILTERS: [string, string][] = [
  ["", "전체"],
  ["ACTIVE", "운영 중"],
  ["PENDING_APPROVAL", "승인 대기"],
  ["INACTIVE", "운영 중지"],
  ["REJECTED", "거부"],
];

const count = (n: number) => n.toLocaleString("ko-KR");
const rate = (used: number, issued: number) => (issued > 0 ? ` (${Math.round((used / issued) * 100)}%)` : "");

const ACTION_LABEL: Record<ApprovalEvent["action"], string> = {
  APPROVE: "승인",
  REJECT: "거부",
  REVIEW_AGAIN: "재심사",
  OWNERSHIP_CHANGE: "소유권 이전",
};

type ActionResult = { id: number; status?: StoreStatus; changed?: boolean; ownerEmail?: string };
type Sheet = { kind: "approve" | "reject" | "ownership"; store: OperatorStore };

const SHEET_TITLE: Record<Sheet["kind"], string> = {
  approve: "매장을 승인할까요?",
  reject: "승인 거부 · 운영 중단",
  ownership: "소유권 이전",
};

/** 감사 로그. 접혀 있을 때는 요청하지 않으려고 부모가 열린 행에서만 렌더한다. */
function ApprovalEvents({ storeId }: { storeId: number }) {
  const q = useQuery({
    queryKey: ["operator-events", storeId],
    queryFn: () => api<ApprovalEvent[]>(`/operator/stores/${storeId}/approval-events`),
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
  const [status, setStatus] = useState("");
  const [draft, setDraft] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [openEvents, setOpenEvents] = useState<number>();
  const [sheet, setSheet] = useState<Sheet>();
  const [value, setValue] = useState("");
  const [tried, setTried] = useState(false);
  const [flash, setFlash] = useState("");
  const [qrStore, setQrStore] = useState<OperatorStore>();

  const summary = useQuery({ queryKey: ["operator-summary"], queryFn: () => api<OperatorSummary>("/operator/summary") });
  const stores = useQuery({
    queryKey: ["operator-stores", status, query, page],
    queryFn: () =>
      api<PageData<OperatorStore>>(
        `/operator/stores?page=${page}&size=20${status ? `&status=${status}` : ""}${query ? `&q=${encodeURIComponent(query)}` : ""}`,
      ),
  });

  const closeSheet = () => {
    setSheet(undefined);
    setValue("");
    setTried(false);
  };

  const act = useMutation({
    mutationFn: ({ id, path, body }: { id: number; path: string; body: Record<string, string> }) =>
      api<ActionResult>(`/operator/stores/${id}/${path}`, { method: "POST", body: JSON.stringify(body) }),
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
    if (blocked) {
      document.getElementById("sheet-input")?.focus();
      return;
    }
    if (sheet.kind === "approve") act.mutate({ id: sheet.store.id, path: "approve", body: { note: value.trim() } });
    if (sheet.kind === "reject") act.mutate({ id: sheet.store.id, path: "reject", body: { reason: value.trim() } });
    if (sheet.kind === "ownership") act.mutate({ id: sheet.store.id, path: "ownership", body: { email: value.trim() } });
  };

  return (
    <OperatorFrame title="매장">
      <dl className="stats" aria-label="매장 현황">
        <div>
          <dt>운영 중</dt>
          <dd>{summary.data?.active ?? "-"}</dd>
        </div>
        <div>
          <dt>승인 대기</dt>
          <dd>{summary.data?.pending ?? "-"}</dd>
        </div>
        <div>
          <dt>운영 중지</dt>
          <dd>{summary.data?.inactive ?? "-"}</dd>
        </div>
        <div>
          <dt>거부</dt>
          <dd>{summary.data?.rejected ?? "-"}</dd>
        </div>
      </dl>

      {summary.data && (
        <p className="hint">
          {summary.data.approvalRequired
            ? "가입 방식: 운영자 승인 후 운영 (STORE_APPROVAL_REQUIRED=true). 새 매장은 승인 대기로 들어옵니다."
            : "가입 방식: 가입 즉시 운영 (STORE_APPROVAL_REQUIRED=false). 승인 없이 바로 운영 중이 됩니다."}
        </p>
      )}


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

      <form
        className="op-toolbar"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          setQuery(draft.trim());
          setPage(0);
        }}
      >
        <div className="field">
          <label htmlFor="operator-query">매장명 또는 사업자등록번호</label>
          <input
            id="operator-query"
            maxLength={100}
            placeholder="전화 온 사장님 매장을 찾으세요"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
          />
        </div>
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
        <div className="sheet-actions">
          <button type="submit" className="btn btn-inline">
            찾기
          </button>
        </div>
      </form>

      {stores.isPending && (
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">매장 목록을 불러오는 중</span>
          <div className="skeleton" style={{ height: 120 }} />
          <div className="skeleton" style={{ height: 120 }} />
        </div>
      )}

      {stores.data?.content.length === 0 && (
        <section className="panel stack">
          <h2>해당하는 매장이 없어요</h2>
          <p className="lead">{status ? "다른 상태를 골라 보세요." : "새 매장이 가입하면 여기에 표시됩니다."}</p>
        </section>
      )}

      <div className="op-cards">
        {stores.data?.content.map((s) => (
          <section className="panel stack op-card" key={s.id}>
            <div className="row">
              <h2>
                <Link href={`/operator/stores/${s.id}`}>{s.name}</Link>
              </h2>
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
                <span className="lead">가입</span>
                <span className="name">{new Date(s.createdAt).toLocaleString("ko-KR")}</span>
              </div>
              <div className="list-item">
                <span className="lead">사업자 확인</span>
                <span className="name">
                  {s.businessVerifiedAt
                    ? `국세청 확인 ${new Date(s.businessVerifiedAt).toLocaleDateString("ko-KR")}`
                    : "확인 기록 없음"}
                </span>
              </div>
              <div className="list-item">
                <span className="lead">요금제</span>
                <span className="name">
                  {s.stats?.plan ? PLAN_LABEL[s.stats.plan] : "-"}
                  {s.stats?.serviceState && (
                    <>
                      {" "}
                      <span className="pill" data-tone={SERVICE_STATE_TONE[s.stats.serviceState]}>
                        {SERVICE_STATE_LABEL[s.stats.serviceState]}
                      </span>
                    </>
                  )}
                </span>
              </div>
            </div>
            {s.stats && (
              <dl className="stats" aria-label={`${s.name} 이용 숫자`}>
                <div>
                  <dt>오늘 게임</dt>
                  <dd>{count(s.stats.gamesToday)}</dd>
                </div>
                <div>
                  <dt>누적 게임</dt>
                  <dd>{count(s.stats.gamesTotal)}</dd>
                </div>
                <div>
                  <dt>쿠폰 발급</dt>
                  <dd>{count(s.stats.couponsIssued)}</dd>
                </div>
                <div>
                  <dt>쿠폰 사용</dt>
                  <dd>
                    {count(s.stats.couponsRedeemed)}
                    <small className="hint">{rate(s.stats.couponsRedeemed, s.stats.couponsIssued)}</small>
                  </dd>
                </div>
              </dl>
            )}
            {s.stats && (
              <p className="hint">
                마지막 참여: {s.stats.lastPlayedAt ? new Date(s.stats.lastPlayedAt).toLocaleString("ko-KR") : "아직 없음"}
              </p>
            )}
            {s.note && <p className="notice">사유: {s.note}</p>}

            <div className="sheet-actions">
              <Link className="btn secondary btn-inline" href={`/operator/stores/${s.id}`}>
                상담 · 결제 관리
              </Link>
              {s.status === "PENDING_APPROVAL" && (
                <button type="button" className="btn btn-inline" onClick={() => setSheet({ kind: "approve", store: s })}>
                  승인
                </button>
              )}
              {s.status !== "REJECTED" && (
                <button type="button" className="btn secondary btn-inline" onClick={() => setSheet({ kind: "reject", store: s })}>
                  {s.status === "PENDING_APPROVAL" ? "거부" : "운영 중단"}
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
              <button type="button" className="btn ghost btn-inline" onClick={() => setQrStore(s)}>
                QR 재발급
              </button>
            </div>

            {/* 접었다 펴는 것은 브라우저가 이미 한다. 열린 행에서만 이력을 불러온다. */}
            <details onToggle={(e) => setOpenEvents(e.currentTarget.open ? s.id : undefined)}>
              <summary>변경 이력</summary>
              {openEvents === s.id && <ApprovalEvents storeId={s.id} />}
            </details>
          </section>
        ))}
      </div>

      <ActivityPager page={page} totalPages={stores.data?.totalPages ?? 0} onChange={setPage} />

      <QrRegenerateDialog store={qrStore} onClose={() => setQrStore(undefined)} onDone={setFlash} />

      <Dialog open={sheet !== undefined} onClose={closeSheet} labelledBy="sheet-title">
        <form className="stack" onSubmit={submitSheet}>
          <h2 id="sheet-title">{sheet ? SHEET_TITLE[sheet.kind] : ""}</h2>

          {sheet && (
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

          {tried && blocked && (
            <p className="notice" role="status">
              {blocked}
            </p>
          )}
          {act.isError && (
            <p className="error" role="alert">
              {errorMessage(act.error)}
            </p>
          )}

          <div className="sheet-actions">
            <button type="button" className="btn ghost" onClick={closeSheet}>
              취소
            </button>
            <button className="btn" disabled={act.isPending}>
              {act.isPending ? "처리 중" : "실행"}
            </button>
          </div>
        </form>
      </Dialog>
    </OperatorFrame>
  );
}
