"use client";
import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { SystemFrame } from "@/features/system/SystemFrame";
import { downloadWithOperatorToken, opsApi } from "@/features/system/api";
import { AUDIT_ACTION, AUDIT_FILTERS, atLeast, consoleError, dateTime } from "@/features/system/labels";
import type { AuditEntry, AuditIntegrity, OperatorMe, PageData } from "@/types/api";

const SIZE = 50;

/**
 * 콘솔에서 일어난 일.
 *
 * 실패한 로그인과 권한 없는 시도가 가장 중요한 줄이라 성공/실패를 눈에 띄게 갈라 두고, 필터도
 * "실패만 보기"를 맨 앞에 뒀다. 각 행은 앞 행의 해시를 품고 있어서 중간을 조용히 지우면 검증이
 * 어디서 끊겼는지 알려 준다.
 */
export default function AuditLog() {
  const [page, setPage] = useState(0);
  const [action, setAction] = useState("");
  const [actor, setActor] = useState("");
  const [failuresOnly, setFailuresOnly] = useState(false);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const me = useQuery({ queryKey: ["ops-me"], queryFn: () => opsApi<OperatorMe>("/me"), retry: false });
  const params = () => {
    const search = new URLSearchParams();
    if (action) search.set("action", action);
    if (actor.trim()) search.set("actor", actor.trim());
    if (failuresOnly) search.set("failuresOnly", "true");
    if (from) search.set("from", from);
    if (to) search.set("to", to);
    return search;
  };

  const q = useQuery({
    queryKey: ["ops-audit", page, action, actor, failuresOnly, from, to],
    queryFn: () => {
      const search = params();
      search.set("page", String(page));
      search.set("size", String(SIZE));
      return opsApi<PageData<AuditEntry>>(`/audit?${search.toString()}`);
    },
    retry: false,
  });

  const verify = useMutation({ mutationFn: () => opsApi<AuditIntegrity>("/audit/verify") });
  const download = useMutation({
    mutationFn: () => downloadWithOperatorToken(`/audit/export?${params().toString()}`, "operator-audit.csv"),
  });

  return (
    <SystemFrame title="접근 기록">
      <section className="panel stack">
        <div className="field">
          <label htmlFor="filter-action">행위</label>
          <select
            id="filter-action"
            value={action}
            onChange={(e) => {
              setPage(0);
              setAction(e.target.value);
            }}
          >
            <option value="">전체</option>
            {AUDIT_FILTERS.map((value) => (
              <option key={value} value={value}>
                {AUDIT_ACTION[value] ?? value}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="filter-actor">계정</label>
          <input
            id="filter-actor"
            value={actor}
            onChange={(e) => {
              setPage(0);
              setActor(e.target.value);
            }}
            placeholder="이메일 일부"
            maxLength={255}
          />
        </div>
        <div className="row">
          <div className="field">
            <label htmlFor="filter-from">시작일</label>
            <input
              id="filter-from"
              type="date"
              value={from}
              onChange={(e) => {
                setPage(0);
                setFrom(e.target.value);
              }}
            />
          </div>
          <div className="field">
            <label htmlFor="filter-to">종료일</label>
            <input
              id="filter-to"
              type="date"
              value={to}
              onChange={(e) => {
                setPage(0);
                setTo(e.target.value);
              }}
            />
          </div>
        </div>
        <label className="check">
          <input
            type="checkbox"
            checked={failuresOnly}
            onChange={(e) => {
              setPage(0);
              setFailuresOnly(e.target.checked);
            }}
          />
          실패한 시도만 보기
        </label>
        <p className="hint">기간을 비우면 최근 30일을 봅니다.</p>
        {atLeast(me.data?.consoleRole, "OPERATOR") && (
          <button className="btn secondary" disabled={download.isPending} onClick={() => download.mutate()}>
            {download.isPending ? "내려받는 중" : "CSV로 내려받기"}
          </button>
        )}
        {download.isError && (
          <p className="error" role="alert">
            {consoleError(download.error)}
          </p>
        )}
      </section>

      {q.isError && (
        <p className="error" role="alert">
          {consoleError(q.error)}
        </p>
      )}
      {q.isPending && <div className="skeleton" style={{ height: 240 }} />}
      {q.data && q.data.content.length === 0 && <p className="lead">해당하는 기록이 없습니다.</p>}
      {q.data && q.data.content.length > 0 && (
        <div className="table-wrap" tabIndex={0}>
          <table className="table">
            <thead>
              <tr>
                <th scope="col">시각</th>
                <th scope="col">계정</th>
                <th scope="col">한 일</th>
                <th scope="col">내용</th>
                <th scope="col">IP</th>
              </tr>
            </thead>
            <tbody>
              {q.data.content.map((row) => (
                <tr key={row.id}>
                  <td>{dateTime(row.createdAt)}</td>
                  <td className="wrap-anywhere">{row.actorEmail}</td>
                  <td>
                    <span className="pill" data-tone={row.succeeded ? "ok" : "off"}>
                      {AUDIT_ACTION[row.action] ?? row.action}
                    </span>
                  </td>
                  <td className="wrap-anywhere">
                    {row.targetType === "STORE" && row.targetId ? `매장 #${row.targetId} ` : ""}
                    {row.detail}
                  </td>
                  <td>{row.ip}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {q.data && q.data.totalPages > 1 && (
        <div className="actions">
          <button className="btn secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            이전
          </button>
          <button
            className="btn secondary"
            disabled={page + 1 >= q.data.totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            다음
          </button>
        </div>
      )}

      {atLeast(me.data?.consoleRole, "OWNER") && (
        <section className="panel stack">
          <h2>기록 무결성</h2>
          <p className="lead">
            각 줄은 앞 줄의 해시를 품고 있습니다. 중간을 고치거나 지우면 그 지점부터 어긋납니다.
          </p>
          <button className="btn secondary" disabled={verify.isPending} onClick={() => verify.mutate()}>
            {verify.isPending ? "검증 중" : "지금 검증"}
          </button>
          {verify.isError && (
            <p className="error" role="alert">
              {consoleError(verify.error)}
            </p>
          )}
          {verify.data &&
            (verify.data.intact ? (
              <p className="success" role="status">
                {verify.data.checked.toLocaleString("ko-KR")}줄 모두 이어져 있습니다.
              </p>
            ) : (
              <p className="error" role="alert">
                {dateTime(verify.data.firstBrokenAt)} 기록(#{verify.data.firstBrokenId})부터 어긋납니다. 기록이 바뀌었을
                수 있습니다.
              </p>
            ))}
        </section>
      )}
    </SystemFrame>
  );
}
