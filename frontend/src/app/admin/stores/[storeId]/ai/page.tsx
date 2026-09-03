"use client";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { AiEventCopyCard, AiImprovementCard, AiManagerChat, AiReportCard } from "@/features/admin/AiCards";
import { api, errorMessage } from "@/lib/api";
import { PLAN_LABEL } from "@/features/admin/labels";
import type { AiStatus } from "@/types/api";

export default function AiPage() {
  const id = String(useParams().storeId);
  const status = useQuery({ queryKey: ["ai-status", id], queryFn: () => api<AiStatus>(`/admin/stores/${id}/ai/status`) });

  if (status.isPending)
    return (
      <AdminFrame title="AI 도우미">
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">AI 사용 현황을 불러오는 중</span>
          <div className="skeleton" style={{ height: 180 }} />
          <div className="skeleton" style={{ height: 180 }} />
        </div>
      </AdminFrame>
    );

  if (status.isError)
    return (
      <AdminFrame title="AI 도우미">
        <p className="error" role="alert">
          {errorMessage(status.error)}
        </p>
      </AdminFrame>
    );

  const plan = status.data.plan;
  const anyAllowed = status.data.features.some((f) => f.allowed);

  return (
    <AdminFrame title="AI 도우미">
      <section className="panel stack">
        <div className="row">
          <h2>{PLAN_LABEL[plan]} 요금제</h2>
          <span className="pill" data-tone="wood">
            {status.data.month}
          </span>
        </div>
        <p className="lead">
          AI는 매장 운영 데이터의 집계만 봅니다. 고객 이름과 전화번호는 어떤 기능에도 전달되지 않습니다.
        </p>
        {!anyAllowed && (
          <p className="notice">
            지금 요금제에는 AI 기능이 포함되어 있지 않습니다. 요금제 화면에서 포함 내용을 확인할 수 있습니다.
          </p>
        )}
      </section>

      <AiEventCopyCard storeId={id} />
      <AiReportCard storeId={id} />
      <AiImprovementCard storeId={id} />
      <AiManagerChat storeId={id} />
    </AdminFrame>
  );
}
