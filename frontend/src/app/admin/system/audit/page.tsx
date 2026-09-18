"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { SystemFrame } from "@/features/system/SystemFrame";
import { opsApi } from "@/features/system/api";
import { AUDIT_ACTION, consoleError, dateTime } from "@/features/system/labels";
import type { AuditEntry, PageData } from "@/types/api";

const SIZE = 50;

/** 콘솔에서 일어난 일. 실패한 로그인이 특히 중요해서 성공/실패를 눈에 띄게 갈라 둔다. */
export default function AuditLog() {
  const [page, setPage] = useState(0);
  const q = useQuery({
    queryKey: ["ops-audit", page],
    queryFn: () => opsApi<PageData<AuditEntry>>(`/audit?page=${page}&size=${SIZE}`),
    retry: false,
  });

  return (
    <SystemFrame title="접근 기록">
      {q.isError && (
        <p className="error" role="alert">
          {consoleError(q.error)}
        </p>
      )}
      {q.isPending && <div className="skeleton" style={{ height: 240 }} />}
      {q.data && q.data.content.length === 0 && <p className="lead">아직 기록이 없습니다.</p>}
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
    </SystemFrame>
  );
}
