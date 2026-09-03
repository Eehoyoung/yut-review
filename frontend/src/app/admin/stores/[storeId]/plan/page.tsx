"use client";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api, errorMessage } from "@/lib/api";
import { AI_FEATURE_LABEL, PLAN_LABEL, PLAN_TAGLINE, priceLabel } from "@/features/admin/labels";
import type { AiFeature, Plan, PlanOption, Subscription } from "@/types/api";

const ENTITLEMENT_LABEL: Record<string, string> = {
  BASIC_ANALYTICS: "기본 통계",
  ADVANCED_ANALYTICS: "상세 분석",
  CSV_EXPORT: "CSV 내보내기",
  BRANDING: "안내물 브랜딩",
};

/** 어떤 등급에서도 잠기지 않는 것들. 사장이 가장 먼저 확인하고 싶어 하는 부분이라 위에 둔다. */
const ALWAYS_INCLUDED = ["윷놀이 게임", "QR 이벤트", "상품·확률 설정", "쿠폰 발급/사용", "직원 PIN", "2일 참여 제한"];

export default function PlanPage() {
  const id = String(useParams().storeId);
  const qc = useQueryClient();
  const current = useQuery({
    queryKey: ["subscription", id],
    queryFn: () => api<Subscription>(`/admin/stores/${id}/subscription`),
  });
  const plans = useQuery({
    queryKey: ["plans", id],
    queryFn: () => api<PlanOption[]>(`/admin/stores/${id}/subscription/plans`),
  });
  const change = useMutation({
    mutationFn: (plan: Plan) =>
      api<Subscription>(`/admin/stores/${id}/subscription`, { method: "PUT", body: JSON.stringify({ plan }) }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["subscription", id] });
      qc.invalidateQueries({ queryKey: ["ai-status", id] });
    },
  });

  if (current.isPending || plans.isPending)
    return (
      <AdminFrame title="요금제">
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">요금제를 불러오는 중</span>
          <div className="skeleton" style={{ height: 120 }} />
          <div className="skeleton" style={{ height: 220 }} />
        </div>
      </AdminFrame>
    );

  if (current.isError || plans.isError)
    return (
      <AdminFrame title="요금제">
        <p className="error" role="alert">
          {errorMessage(current.error ?? plans.error)}
        </p>
      </AdminFrame>
    );

  return (
    <AdminFrame title="요금제">
      <section className="panel stack">
        <div className="row">
          <h2>{PLAN_LABEL[current.data.plan]} 사용 중</h2>
          <span className="pill" data-tone="wood">{priceLabel(current.data.monthlyPriceKrw)}</span>
        </div>
        <p className="lead">
          윷놀이와 고객 경험은 요금제와 무관하게 모두 같습니다. 등급 차이는 분석과 AI, 안내물 브랜딩에만
          있습니다.
        </p>
        <div className="list">
          {ALWAYS_INCLUDED.map((item) => (
            <div className="list-item" key={item}>
              <span className="name">{item}</span>
              <span className="pill" data-tone="ok">모든 요금제</span>
            </div>
          ))}
        </div>
      </section>

      {plans.data.map((option) => {
        const isCurrent = option.plan === current.data.plan;
        return (
          <section className="panel stack" key={option.plan}>
            <div className="row">
              <h2>{PLAN_LABEL[option.plan]}</h2>
              <span className="pill" data-tone={isCurrent ? "ok" : "wood"}>
                {isCurrent ? "사용 중" : PLAN_TAGLINE[option.plan]}
              </span>
            </div>
            <p className="result-copy">{priceLabel(option.monthlyPriceKrw)}</p>
            <div className="list">
              {option.entitlements.map((e) => (
                <div className="list-item" key={e}>
                  <span className="name">{ENTITLEMENT_LABEL[e] ?? e}</span>
                </div>
              ))}
              {(Object.keys(option.monthlyAiQuota) as AiFeature[]).map((feature) => (
                <div className="list-item" key={feature}>
                  <span className="name">{AI_FEATURE_LABEL[feature]}</span>
                  <span className="pill">월 {option.monthlyAiQuota[feature]}회</span>
                </div>
              ))}
              <div className="list-item">
                <span className="lead">분석 데이터 보관</span>
                <span className="name">
                  {option.analyticsRetentionDays > 0 ? `${option.analyticsRetentionDays}일` : "장기 집계"}
                </span>
              </div>
              {option.automaticWeeklyReport && (
                <div className="list-item">
                  <span className="name">주간 리포트 자동 생성</span>
                </div>
              )}
            </div>
            {!isCurrent && (
              <button className="btn secondary" disabled={change.isPending} onClick={() => change.mutate(option.plan)}>
                {PLAN_LABEL[option.plan]}로 변경
              </button>
            )}
          </section>
        );
      })}

      {change.isError && (
        <p className="error" role="alert">
          {errorMessage(change.error)}
        </p>
      )}
      <p className="hint">
        고객 개인정보 보관 기준(120일)은 요금제와 무관하게 동일합니다. 위 보관 기간은 이미 비식별인 분석
        집계에만 적용됩니다.
      </p>
    </AdminFrame>
  );
}
