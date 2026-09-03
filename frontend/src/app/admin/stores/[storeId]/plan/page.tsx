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
  BRANDING: "안내물 문구",
};

/**
 * 어떤 등급에서도 잠기지 않는 것들.
 *
 * 이 목록이 비교표보다 먼저 오는 이유는, 사장이 요금제 화면에서 가장 먼저 걱정하는 것이 "돈을 안
 * 내면 손님이 못 노나?"이기 때문이다. 답이 "그런 일은 없다"이고 그게 이 제품의 약속이다.
 */
const ALWAYS_INCLUDED = ["윷놀이 게임", "QR 이벤트", "상품·확률 설정", "쿠폰 발급/사용", "직원 PIN", "2일 참여 제한"];

const ORDER: Plan[] = ["BASIC", "STANDARD", "PRO"];

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
      qc.invalidateQueries({ queryKey: ["analytics", id] });
    },
  });

  if (current.isPending || plans.isPending)
    return (
      <AdminFrame title="요금제">
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">요금제를 불러오는 중</span>
          <div className="skeleton" style={{ height: 120 }} />
          <div className="skeleton" style={{ height: 260 }} />
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

  const now = current.data.plan;
  const nowIndex = ORDER.indexOf(now);
  const byPlan = new Map(plans.data.map((p) => [p.plan, p]));

  /** 지금 등급에 이미 있는 것은 빼고, 올리면 더해지는 것만 보여 준다. */
  function addedBy(option: PlanOption) {
    const mine = byPlan.get(now);
    const has = new Set([...(mine?.entitlements ?? []), ...(mine?.aiFeatures ?? [])]);
    return [
      ...option.entitlements.filter((e) => !has.has(e)).map((e) => ENTITLEMENT_LABEL[e] ?? e),
      ...option.aiFeatures.filter((f) => !has.has(f)).map((f) => AI_FEATURE_LABEL[f]),
    ];
  }

  return (
    <AdminFrame title="요금제">
      {/* 지금 무엇을 쓰고 있는지가 첫 줄. 비교표보다 먼저 온다. */}
      <section className="insight">
        <p className="insight-when">사용 중</p>
        <h2 className="insight-title">
          {PLAN_LABEL[now]} · {priceLabel(current.data.monthlyPriceKrw)}
        </h2>
        <p className="insight-summary">{PLAN_TAGLINE[now]}</p>
      </section>

      <section className="panel stack">
        <h2>모든 요금제에 들어 있는 것</h2>
        <p className="lead">
          윷놀이와 손님이 겪는 흐름은 등급과 무관하게 전부 같습니다. 등급 차이는 매장 운영을 돕는
          기능에서만 생깁니다.
        </p>
        <ul className="included">
          {ALWAYS_INCLUDED.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      </section>

      {ORDER.map((plan, i) => {
        const option = byPlan.get(plan);
        if (!option) return null;
        const isCurrent = plan === now;
        const isUpgrade = i > nowIndex;
        const added = addedBy(option);
        return (
          <section className="panel stack" key={plan}>
            <div className="row">
              <h2>{PLAN_LABEL[plan]}</h2>
              <span className="pill" data-tone={isCurrent ? "ok" : "wood"}>
                {isCurrent ? "사용 중" : priceLabel(option.monthlyPriceKrw)}
              </span>
            </div>

            {isCurrent ? (
              <p className="lead">지금 쓰고 있는 요금제입니다.</p>
            ) : isUpgrade ? (
              <>
                <p className="lead">올리면 이만큼 더 열립니다.</p>
                <ul className="included">
                  {added.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </>
            ) : (
              <p className="lead">지금보다 낮은 등급입니다. 내리면 위 기능이 잠깁니다.</p>
            )}

            <div className="list">
              <div className="list-item">
                <span className="lead">분석 데이터 보관</span>
                <span className="name">
                  {option.analyticsRetentionDays > 0 ? `${option.analyticsRetentionDays}일` : "장기 집계"}
                </span>
              </div>
              {(Object.keys(option.monthlyAiQuota) as AiFeature[]).map((feature) => (
                <div className="list-item" key={feature}>
                  <span className="lead">{AI_FEATURE_LABEL[feature]}</span>
                  <span className="name">월 {option.monthlyAiQuota[feature]}회</span>
                </div>
              ))}
              {option.automaticWeeklyReport && (
                <div className="list-item">
                  <span className="lead">주간 리포트</span>
                  <span className="name">월요일 자동 생성</span>
                </div>
              )}
            </div>

            {!isCurrent && (
              <button className="btn secondary" disabled={change.isPending} onClick={() => change.mutate(plan)}>
                {PLAN_LABEL[plan]}로 변경 요청
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
      {change.isSuccess && (
        <p className="success" role="status">
          요금제를 변경했습니다.
        </p>
      )}
      <p className="hint">
        결제 연동 전이라 요금제 변경은 운영자가 처리합니다. 변경 버튼은 권한이 있는 계정에서만 즉시
        반영되고, 그 외에는 안내 메시지가 표시됩니다.
      </p>
      <p className="hint">
        고객 개인정보 보관 기준(120일)은 요금제와 무관하게 같습니다. 위 보관 기간은 이미 비식별인 분석
        집계에만 적용됩니다.
      </p>
    </AdminFrame>
  );
}
