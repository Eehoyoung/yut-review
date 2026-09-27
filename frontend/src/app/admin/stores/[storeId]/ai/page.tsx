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
      <section className="panel stack admin-section" aria-labelledby="ai-start-title">
        <p className="eyebrow">매장 운영 질문</p>
        <h2 id="ai-start-title">무엇을 확인할까요?</h2>
        <p className="lead">참여 흐름을 분석하고, 다음 이벤트에 적용할 개선안을 찾을 수 있어요.</p>
        <ul className="included" aria-label="AI 도우미로 할 수 있는 일">
          <li>최근 참여와 쿠폰 사용 흐름 분석</li>
          <li>한 번에 검증할 개선 실험 제안</li>
          <li>매장 안내 문구 작성</li>
        </ul>
      </section>
      {anyAllowed ? (
        <section className="admin-section" aria-label="최근 AI 분석">
          <AiInsightCard storeId={id} />
        </section>
      ) : (
        <section className="panel stack">
          <h2>{PLAN_LABEL[plan]} 요금제에는 AI 기능이 없어요.</h2>
          <p className="lead">
            베이직·스탠다드: AI 매장 분석 / 프로: 이벤트 문구·개선 제안·AI 대화
          </p>
          <Link className="btn secondary" href={`/admin/stores/${id}/plan`}>
            요금제 보기
          </Link>
        </section>
      )}

      {anyAllowed && (
        <section className="stack admin-section" aria-label="AI 작업 도구">
          <AiReportCard storeId={id} />
          <AiImprovementCard storeId={id} />
          <AiEventCopyDialog storeId={id} />
          <AiManagerChat storeId={id} />
          <p className="hint">
            AI에는 익명 집계만 전달합니다. 고객 이름과 전화번호는 전달하지 않습니다. 사용량 기준: {status.data.month}
          </p>
        </section>
      )}
    </AdminFrame>
  );
}
