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
      <section className="panel stack" aria-label="AI 연결 상태">
        <div className="row">
          <h2>AI 연결</h2>
          <span className="pill" data-tone={status.data.liveProviderReady ? "ok" : "wait"}>
            {status.data.liveProviderReady ? "API 키 연결됨" : "테스트 응답 모드"}
          </span>
        </div>
        <p className="lead">
          {status.data.liveProviderReady
            ? `첫 실제 호출 대기 · 분석 ${status.data.models.analysis} · 대화 ${status.data.models.chat}`
            : "서버에 OPENAI_API_KEY를 추가하면 재배포 후 실제 OpenAI 호출로 자동 전환됩니다."}
        </p>
        <p className="hint">API 키 값은 화면·응답·로그에 표시하지 않습니다. 현재 공급자: {status.data.provider}</p>
      </section>
      {anyAllowed ? (
        <AiInsightCard storeId={id} />
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
        <>
          <AiReportCard storeId={id} />
          <AiImprovementCard storeId={id} />
          <AiEventCopyDialog storeId={id} />
          <AiManagerChat storeId={id} />
          <p className="hint">
            AI에는 익명 집계만 전달합니다. 고객 이름과 전화번호는 전달하지 않습니다. 사용량 기준: {status.data.month}
          </p>
        </>
      )}
    </AdminFrame>
  );
}
