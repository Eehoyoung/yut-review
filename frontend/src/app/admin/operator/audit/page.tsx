"use client";
import Link from "next/link";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { OperatorFrame } from "@/features/admin/OperatorFrame";
import { api, errorMessage } from "@/lib/api";
import type { OperatorAuditEntry } from "@/types/api";

/**
 * 운영자가 한 일을 한 줄씩 시간순으로 본다.
 *
 * 매장 심사(`store_approval_events`)와 계정 변경(`operator_audit_events`)을 서버가 합쳐서 준다.
 * 둘을 따로 보면 "누가 이 매장을 승인했지"는 알 수 있어도 "그 사람이 언제 운영자가 됐지"는
 * 알 수 없다. 분쟁이 났을 때 필요한 것은 두 번째 질문이다.
 *
 * 서버가 최근 200건으로 잘라서 준다. 페이지네이션을 붙이지 않는 것은 운영자 동작이 하루 수십 건
 * 규모이기 때문이다. 200건을 넘겨서 봐야 할 일이 생기면 그때가 페이지네이션을 붙일 때다.
 */

const ACTION_LABEL: Record<OperatorAuditEntry["action"], string> = {
  APPROVE: "승인",
  REJECT: "거부",
  REVIEW_AGAIN: "재심사",
  OWNERSHIP_CHANGE: "소유권 이전",
  OPERATOR_CREATED: "운영자 계정 신설",
  OPERATOR_GRANTED: "운영자 권한 부여",
  OPERATOR_REVOKED: "운영자 권한 회수",
};

/** 되돌리기 어려운 동작은 눈에 띄어야 한다. 나머지는 조용히 흐른다. */
const ACTION_TONE: Record<OperatorAuditEntry["action"], string> = {
  APPROVE: "brand",
  REJECT: "warn",
  REVIEW_AGAIN: "muted",
  OWNERSHIP_CHANGE: "warn",
  OPERATOR_CREATED: "warn",
  OPERATOR_GRANTED: "warn",
  OPERATOR_REVOKED: "warn",
};

const FILTERS: [string, string][] = [
  ["", "전체"],
  ["STORE", "매장 심사"],
  ["ACCOUNT", "계정 변경"],
];

export default function OperatorAuditPage() {
  const [kind, setKind] = useState("");

  const feed = useQuery({
    queryKey: ["operator-audit"],
    queryFn: () => api<OperatorAuditEntry[]>("/admin/operator/audit"),
  });

  const rows = (feed.data ?? []).filter((e) => !kind || e.kind === kind);

  return (
    <OperatorFrame title="활동 기록">
      <section className="panel stack">
        <h2>운영자가 한 일</h2>
        <p className="lead">
          매장 심사와 계정 변경을 시간순으로 합쳐 최근 200건까지 보여 줍니다. 기록은 지워지지 않습니다.
        </p>
      </section>

      <div className="field">
        <label htmlFor="audit-filter">종류</label>
        <select id="audit-filter" value={kind} onChange={(e) => setKind(e.target.value)}>
          {FILTERS.map(([v, label]) => (
            <option key={label} value={v}>
              {label}
            </option>
          ))}
        </select>
      </div>

      {feed.isError && (
        <p className="error" role="alert">
          {errorMessage(feed.error)}
        </p>
      )}

      {feed.isPending && (
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">활동 기록을 불러오는 중</span>
          <div className="skeleton" style={{ height: 72 }} />
          <div className="skeleton" style={{ height: 72 }} />
          <div className="skeleton" style={{ height: 72 }} />
        </div>
      )}

      {feed.data && rows.length === 0 && (
        <section className="panel stack">
          <h2>기록이 없어요</h2>
          <p className="lead">{kind ? "다른 종류를 골라 보세요." : "운영자 동작이 아직 없습니다."}</p>
        </section>
      )}

      {rows.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <caption className="visually-hidden">운영자 활동 기록</caption>
            <thead>
              <tr>
                <th scope="col">시각</th>
                <th scope="col">동작</th>
                <th scope="col">대상</th>
                <th scope="col">실행</th>
                <th scope="col">메모</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e, i) => (
                // 서버가 내려준 순서가 곧 의미다. id가 두 테이블에서 겹치므로 시각과 순서를 키로 쓴다.
                <tr key={`${e.createdAt}-${i}`}>
                  <td>{new Date(e.createdAt).toLocaleString("ko-KR")}</td>
                  <td>
                    <span className="pill" data-tone={ACTION_TONE[e.action]}>
                      {ACTION_LABEL[e.action]}
                    </span>
                  </td>
                  <td className="wrap-anywhere">
                    {e.kind === "STORE" && e.storeId ? (
                      <Link href={`/admin/stores/${e.storeId}/dashboard`}>{e.target ?? "-"}</Link>
                    ) : (
                      (e.target ?? "-")
                    )}
                  </td>
                  <td className="wrap-anywhere">{e.actor ?? "-"}</td>
                  <td className="wrap-anywhere">{e.note ?? "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </OperatorFrame>
  );
}
