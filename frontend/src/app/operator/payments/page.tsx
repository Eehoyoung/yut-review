"use client";
import Link from "next/link";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ActivityPager } from "@/features/admin/ActivityTable";
import { PLAN_LABEL } from "@/features/admin/labels";
import { OperatorFrame } from "@/features/operator/OperatorFrame";
import { PAYMENT_STATUS_LABEL, PAYMENT_STATUS_TONE, won } from "@/features/operator/labels";
import { api, errorMessage } from "@/lib/api";
import type { OperatorPayment, PageData, SubscriptionPaymentStatus } from "@/types/api";

/**
 * 결제 원장. 전 매장의 결제 시도를 최근 순으로 본다.
 *
 * "결과 확인 중"(PENDING)이 오래 남아 있으면 포트원에서 결제는 됐는데 우리 기록이 못 따라온 경우일 수 있다.
 * 결제 번호로 포트원 콘솔과 대조한다. 환불·부분취소는 아직 포트원 콘솔에서 한다.
 */

const STATUSES: SubscriptionPaymentStatus[] = ["PAID", "FAILED", "PENDING"];
const time = (s?: string | null) => (s ? new Date(s).toLocaleString("ko-KR") : "-");

export default function OperatorPaymentsPage() {
  const [status, setStatus] = useState<SubscriptionPaymentStatus | "">("");
  const [draft, setDraft] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);

  const q = useQuery({
    queryKey: ["operator-payments", status, query, page],
    queryFn: () =>
      api<PageData<OperatorPayment>>(
        `/operator/payments?page=${page}&size=30${status ? `&status=${status}` : ""}${query ? `&q=${encodeURIComponent(query)}` : ""}`,
      ),
  });

  return (
    <OperatorFrame title="결제">
      <p className="lead">
        전 매장의 요금제 결제 시도입니다. 환불·부분취소는 포트원 콘솔에서 하고, 오래 &lsquo;결과 확인 중&rsquo;인 건은 결제 번호로
        포트원 콘솔과 대조하세요.
      </p>

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
          <label htmlFor="payment-query">매장명 또는 결제 번호</label>
          <input id="payment-query" maxLength={100} value={draft} onChange={(e) => setDraft(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="payment-status">상태</label>
          <select
            id="payment-status"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as SubscriptionPaymentStatus | "");
              setPage(0);
            }}
          >
            <option value="">전체</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {PAYMENT_STATUS_LABEL[s]}
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

      {q.isError && (
        <p className="error" role="alert">
          {errorMessage(q.error)}
        </p>
      )}
      {q.isPending && <div className="skeleton" style={{ height: 240 }} aria-busy="true" />}

      {q.data && q.data.content.length === 0 && <p className="hint">해당하는 결제가 없습니다.</p>}
      {q.data && q.data.content.length > 0 && (
        <div className="table-wrap">
          <table className="table">
            <caption className="visually-hidden">요금제 결제 원장</caption>
            <thead>
              <tr>
                <th scope="col">시도</th>
                <th scope="col">매장</th>
                <th scope="col">등급</th>
                <th scope="col">금액</th>
                <th scope="col">상태</th>
                <th scope="col">결제 완료</th>
                <th scope="col">결제 번호 · 실패 사유</th>
              </tr>
            </thead>
            <tbody>
              {q.data.content.map((p) => (
                <tr key={p.id}>
                  <td>{time(p.createdAt)}</td>
                  <td className="wrap-anywhere">
                    <Link href={`/operator/stores/${p.storeId}`}>{p.storeName}</Link>
                  </td>
                  <td>{PLAN_LABEL[p.plan]}</td>
                  <td>{won(p.amount)}</td>
                  <td>
                    <span className="pill" data-tone={PAYMENT_STATUS_TONE[p.status]}>
                      {PAYMENT_STATUS_LABEL[p.status]}
                    </span>
                  </td>
                  <td>{time(p.paidAt)}</td>
                  <td className="wrap-anywhere">
                    <small className="hint">{p.paymentId}</small>
                    {p.failureReason && (
                      <>
                        <br />
                        {p.failureReason}
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {q.data && <ActivityPager page={page} totalPages={q.data.totalPages} onChange={setPage} />}
    </OperatorFrame>
  );
}
