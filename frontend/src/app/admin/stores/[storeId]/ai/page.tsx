"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import {
  AiEventCopyDialog,
  AiImprovementCard,
  AiInsightCard,
  AiManagerChat,
  AiReportCard,
} from "@/features/admin/AiCards";
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
      {anyAllowed ? (
        <AiInsightCard storeId={id} />
      ) : (
        <section className="panel stack">
          <h2>{PLAN_LABEL[plan]} 요금제에는 AI가 없어요</h2>
          <p className="lead">
            스탠다드부터 안내 문구와 운영 리포트를, 프로부터 개선 제안과 AI 매니저를 쓸 수 있습니다.
          </p>
          <Link className="btn secondary" href={`/admin/stores/${id}/plan`}>
            요금제 보기
          </Link>
        </section>
      )}

      {anyAllowed && (
        <>
          <AiReportCard storeId={id} />
          <AiImprovementCard storeId={id} />
          <AiEventCopyDialog storeId={id} />
          <AiManagerChat storeId={id} />
          <p className="hint">
            AI는 매장 운영 데이터의 집계만 봅니다. 고객 이름과 전화번호는 어떤 기능에도 전달되지
            않습니다. 이번 달 사용량은 {status.data.month} 기준입니다.
          </p>
        </>
      )}
    </AdminFrame>
  );
}
