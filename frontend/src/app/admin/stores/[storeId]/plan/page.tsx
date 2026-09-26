"use client";
import { useEffect, useRef, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api, errorMessage } from "@/lib/api";
import {
  AI_FEATURE_LABEL,
  PAYMENT_STATUS_LABEL,
  PG_LABEL,
  PLAN_LABEL,
  PLAN_TAGLINE,
  priceLabel,
} from "@/features/admin/labels";
import type { AiFeature, Billing, Plan, PlanOption, Subscription } from "@/types/api";

const ENTITLEMENT_LABEL: Record<string, string> = {
  BASIC_ANALYTICS: "기본 통계",
  ADVANCED_ANALYTICS: "상세 분석",
  CSV_EXPORT: "CSV 내보내기",
  BRANDING: "안내물 브랜딩",
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
  const billing = useQuery({
    queryKey: ["billing", id],
    queryFn: () => api<Billing>(`/admin/stores/${id}/billing`),
    // 2026-09-27: 결제대행사는 KG이니시스 단일로 연다. 서버의 토스페이먼츠 연동은 남겨 두고 화면에서만 뺀다.
    select: (data) => ({ ...data, channels: data.channels.filter((c) => c.pg === "INICIS") }),
  });
  const [channelKey, setChannelKey] = useState("");
  const [sdkError, setSdkError] = useState("");
  const refresh = () => {
    for (const key of ["subscription", "billing", "ai-status", "analytics"]) qc.invalidateQueries({ queryKey: [key, id] });
  };
  const checkout = useMutation({
    mutationFn: ({ plan, billingKey }: { plan: Plan; billingKey: string }) =>
      api(`/admin/stores/${id}/billing/checkout`, { method: "POST", body: JSON.stringify({ plan, billingKey }) }),
    onSuccess: refresh,
  });
  const autoRenew = useMutation({
    mutationFn: (on: boolean) =>
      api(`/admin/stores/${id}/billing/auto-renew`, { method: "PUT", body: JSON.stringify({ on }) }),
    onSuccess: refresh,
  });

  /**
   * 모바일 결제창은 팝업이 아니라 페이지를 떠났다가 redirectUrl로 돌아온다. 돌아온 주소의
   * billingKey로 결제를 마저 한다. 새로고침으로 두 번 청구되지 않게 주소부터 지운다(서버에도 잠금이 있다).
   */
  const resumed = useRef(false);
  const { mutate: finishCheckout } = checkout;
  useEffect(() => {
    if (resumed.current) return;
    resumed.current = true;
    const q = new URLSearchParams(window.location.search);
    const plan = q.get("plan") as Plan | null;
    const billingKey = q.get("billingKey");
    if (!plan || (!billingKey && !q.get("code"))) return;
    window.history.replaceState(null, "", window.location.pathname);
    if (q.get("code")) setSdkError(q.get("message") ?? "결제수단을 등록하지 못했어요.");
    else if (billingKey) finishCheckout({ plan, billingKey });
  }, [finishCheckout]);

  async function register(plan: Plan) {
    const info = billing.data;
    const channel = channelKey || info?.channels[0]?.channelKey;
    if (!info || !channel) return;
    setSdkError("");
    const PortOne = await import("@portone/browser-sdk/v2");
    const result = await PortOne.requestIssueBillingKey({
      storeId: info.portoneStoreId,
      channelKey: channel,
      billingKeyMethod: "CARD",
      issueId: `bk${id}t${Date.now()}`,
      issueName: `소담한판 ${PLAN_LABEL[plan]} 월 정기결제`,
      displayAmount: plans.data?.find((p) => p.plan === plan)?.monthlyPriceKrw,
      currency: "KRW",
      // KG이니시스 모바일은 제공 기간이, PC는 이름·연락처·이메일이 없으면 결제창이 열리지 않는다.
      offerPeriod: { interval: "1m" },
      customer: {
        customerId: info.customer.customerId,
        fullName: info.customer.fullName,
        phoneNumber: info.customer.phoneNumber || undefined,
        email: info.customer.email,
      },
      redirectUrl: `${window.location.origin}${window.location.pathname}?plan=${plan}`,
    });
    if (!result) return; // 모바일: 페이지를 떠났다가 위 useEffect로 돌아온다.
    if (result.code !== undefined) {
      setSdkError(result.message ?? "결제수단을 등록하지 못했어요.");
      return;
    }
    checkout.mutate({ plan, billingKey: result.billingKey });
  }

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
  const pay = billing.data;
  const canPay = !!pay?.enabled && pay.channels.length > 0;
  const state = pay?.serviceState;
  // 결제로 산 기간 안인가. 이때만 같은 등급 카드 변경·다음 결제부터 내리기가 된다.
  const paidPeriod = state === "ACTIVE" && !!pay?.lastPaidAt;
  const date = (iso?: string) => (iso ? new Date(iso).toLocaleDateString("ko-KR") : "-");
  // 제한은 D+3 00:00부터라 사람에게는 그 전날(D+2)까지라고 말한다.
  const lastServiceDay = pay?.restrictedFrom ? date(new Date(new Date(pay.restrictedFrom).getTime() - 1).toISOString()) : "-";
  const busy = checkout.isPending || autoRenew.isPending;
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
        {current.data.trial && current.data.trialEndsAt && (
          <p className="insight-summary">
            가입일 기준 14일 무료체험 · {new Date(current.data.trialEndsAt).toLocaleString("ko-KR")}에 종료
          </p>
        )}
      </section>

      {canPay && (
        <section className="panel stack">
          <h2>결제</h2>
          {state === "RESTRICTED" && (
            <p className="error" role="alert">
              결제가 확인되지 않아 이용이 중지됐어요. 카드를 등록해 결제하면 바로 다시 열려요.
            </p>
          )}
          {state === "GRACE" && (
            <p className="error" role="alert">
              결제가 확인되지 않았어요. {lastServiceDay}까지 결제하지 않으면 손님 이벤트와 매장 관리가 중지돼요.
            </p>
          )}
          {state === "TRIAL" && (
            <p className="lead">
              무료체험 중이에요. 카드를 등록해 두면 {date(pay.nextBillingAt)}에 고른 요금제로 첫 결제가 돼요.
              등록하지 않으면 {lastServiceDay}까지만 이용할 수 있어요.
            </p>
          )}
          {state === "OPEN" && <p className="lead">카드를 등록하면 오늘 첫 달이 결제되고, 매달 같은 날 자동으로 결제돼요.</p>}
          {(pay.lastPaidAt || pay.nextBillingAt) && (
            <div className="list">
              <div className="list-item">
                <span className="lead">최근 결제일</span>
                <span className="name">{date(pay.lastPaidAt)}</span>
              </div>
              <div className="list-item">
                <span className="lead">{pay.hasCard && pay.autoRenew ? "다음 결제일" : "결제예정일"}</span>
                <span className="name">{date(pay.nextBillingAt)}</span>
              </div>
              {pay.nextPlan && (
                <div className="list-item">
                  <span className="lead">다음 결제부터</span>
                  <span className="name">{PLAN_LABEL[pay.nextPlan]}</span>
                </div>
              )}
              {pay.pg && (
                <div className="list-item">
                  <span className="lead">결제수단</span>
                  <span className="name">카드 · {PG_LABEL[pay.pg]}</span>
                </div>
              )}
            </div>
          )}
          {pay.channels.length > 1 && (
            <label className="stack">
              <span className="lead">결제대행사</span>
              <select value={channelKey || pay.channels[0].channelKey} onChange={(e) => setChannelKey(e.target.value)}>
                {pay.channels.map((c) => (
                  <option key={c.channelKey} value={c.channelKey}>
                    {PG_LABEL[c.pg]}
                  </option>
                ))}
              </select>
            </label>
          )}
          {paidPeriod && (
            <button className="btn secondary" disabled={busy} onClick={() => register(now)}>
              카드 변경
            </button>
          )}
          {pay.hasCard && state !== "RESTRICTED" && (
            <button className="btn secondary" disabled={busy} onClick={() => autoRenew.mutate(!pay.autoRenew)}>
              {pay.autoRenew ? "자동결제 해지" : "자동결제 다시 켜기"}
            </button>
          )}
          {pay.payments.length > 0 && (
            <div className="list">
              {pay.payments.map((p) => (
                <div className="list-item" key={p.createdAt}>
                  <span className="lead">
                    {new Date(p.createdAt).toLocaleDateString("ko-KR")} · {PLAN_LABEL[p.plan]}
                    {p.failureReason && ` · ${p.failureReason}`}
                  </span>
                  <span className="name">
                    {p.amount.toLocaleString("ko-KR")}원 {PAYMENT_STATUS_LABEL[p.status]}
                  </span>
                </div>
              ))}
            </div>
          )}
          <p className="hint">
            해지해도 다음 결제일까지 쓸 수 있어요. 결제가 되지 않으면 결제일 이틀 뒤까지 이용한 다음 중지되고, 데이터는 지워지지 않아요.
          </p>
        </section>
      )}

      <section className="panel stack">
        <h2>모든 요금제 공통</h2>
        <p className="lead">
          게임, QR, 상품, 쿠폰은 모든 요금제에서 같습니다.
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
            <p className="lead">{PLAN_TAGLINE[plan]}</p>

            {isCurrent ? (
              <p className="hint">현재 요금제</p>
            ) : isUpgrade ? (
              <>
                <p className="lead">추가되는 기능</p>
                <ul className="included">
                  {added.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ul>
              </>
            ) : (
              <p className="lead">변경하면 일부 기능을 사용할 수 없습니다.</p>
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

            {canPay
              ? !(paidPeriod && isCurrent) && (
                  <button className="btn" disabled={busy} onClick={() => register(plan)}>
                    {state === "TRIAL"
                      ? pay.nextPlan === plan
                        ? `체험 후 ${PLAN_LABEL[plan]} 결제 예정 · 카드 다시 등록`
                        : `카드 등록 · 체험 후 ${PLAN_LABEL[plan]} ${priceLabel(option.monthlyPriceKrw)}`
                      : paidPeriod && !isUpgrade
                        ? `다음 결제부터 ${PLAN_LABEL[plan]}`
                        : `카드 등록하고 ${PLAN_LABEL[plan]} 결제 · ${priceLabel(option.monthlyPriceKrw)}`}
                  </button>
                )
              : !isCurrent && (
                  <button className="btn secondary" disabled={change.isPending} onClick={() => change.mutate(plan)}>
                    {PLAN_LABEL[plan]}로 변경
                  </button>
                )}
          </section>
        );
      })}

      {(sdkError || checkout.isError || autoRenew.isError) && (
        <p className="error" role="alert">
          {sdkError || errorMessage(checkout.error ?? autoRenew.error)}
        </p>
      )}
      {checkout.isSuccess && (
        <p className="success" role="status">
          결제수단을 등록했어요.
        </p>
      )}
      {change.isError && (
        <p className="error" role="alert">
          {errorMessage(change.error)}
        </p>
      )}
      {change.isSuccess && (
        <p className="success" role="status">
          요금제를 변경했어요.
        </p>
      )}
      {!canPay && <p className="hint">지금은 온라인 결제를 받지 않아요. 요금제 변경은 소담랩스에 문의해 주세요.</p>}
      <p className="hint">
        집계 보관 기간과 브랜딩·AI 운영 기능이 요금제별로 다릅니다. 고객 개인정보는 모든 요금제에서 120일 보관합니다.
      </p>
    </AdminFrame>
  );
}
