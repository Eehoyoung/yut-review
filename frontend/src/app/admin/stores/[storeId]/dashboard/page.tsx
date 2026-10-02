"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { Dialog } from "@/features/ui/Dialog";
import { ApiClientError, api, errorMessage } from "@/lib/api";
import { AiInsightCard } from "@/features/admin/AiCards";
import { RetrySettingsPanel } from "@/features/admin/RetrySettingsPanel";
import { ExampleSearch } from "@/features/examples/ExampleSearch";
import { STORE_STATUS_HINT, STORE_STATUS_LABEL, STORE_STATUS_TONE } from "@/features/admin/labels";
import type { AiStatus, StoreStatus, Summary } from "@/types/api";

// 매장 정보 편집은 "매장 정보"(settings) 화면으로 옮겼다. 여기서는 상태·안내에 필요한 칸만 읽는다.
type StoreDetail = { id: number; name: string; address: string; serviceSuspended?: boolean; status?: StoreStatus; approvalNote?: string };
const PAYMENT_RESTRICTION_CODES = new Set(["SUBSCRIPTION_PAYMENT_REQUIRED", "STORE_PAYMENT_REQUIRED"]);

export default function Dashboard() {
  const id = String(useParams().storeId);
  const [showPaymentNotice, setShowPaymentNotice] = useState(false);
  const summary = useQuery({
    queryKey: ["analytics", id],
    queryFn: () => api<Summary>(`/admin/stores/${id}/analytics/summary`),
  });
  const store = useQuery({
    queryKey: ["store", id],
    queryFn: () => api<StoreDetail>(`/admin/stores/${id}`),
  });

  const ai = useQuery({
    queryKey: ["ai-status", id],
    queryFn: () => api<AiStatus>(`/admin/stores/${id}/ai/status`),
    retry: false,
  });
  const aiOpen = ai.data?.features.some((feature) => feature.allowed) === true;
  const paymentRestricted = store.data?.serviceSuspended === true
    || (summary.error instanceof ApiClientError && PAYMENT_RESTRICTION_CODES.has(summary.error.code));
  useEffect(() => {
    if (paymentRestricted) setShowPaymentNotice(true);
  }, [paymentRestricted]);
  // ACTIVE가 아니면 운영 엔드포인트가 전부 403이다. 오류 덩어리 대신 무엇을 기다리는지 말한다.
  // GET /admin/stores/{id}만 상태 확인용으로 열려 있어 여기서 판단할 수 있다.
  const status = store.data?.status;
  const couponTotal = (summary.data?.issuedCoupons ?? 0) + (summary.data?.redeemedCoupons ?? 0);
  const redemptionRate = couponTotal
    ? Math.round(((summary.data?.redeemedCoupons ?? 0) / couponTotal) * 100)
    : null;

  if (status && status !== "ACTIVE")
    return (
      <AdminFrame title="대시보드">
        <section className="panel stack">
          <div className="row">
            <h2>{store.data?.name}</h2>
            <span className="pill" data-tone={STORE_STATUS_TONE[status]}>{STORE_STATUS_LABEL[status]}</span>
          </div>
          <p className="lead" role="status">{STORE_STATUS_HINT[status]}</p>
          {store.data?.approvalNote && <p className="notice">사유: {store.data.approvalNote}</p>}
          <Link className="btn secondary" href="/admin">내 매장으로</Link>
        </section>
      </AdminFrame>
    );

  return (
    <AdminFrame title="대시보드">
      <Dialog open={showPaymentNotice} onClose={() => setShowPaymentNotice(false)} labelledBy="payment-notice-title">
        <div className="payment-notice-symbol" aria-hidden="true"><span>!</span></div>
        <div className="stack payment-notice-copy">
          <p className="eyebrow">구독 상태 확인</p>
          <h2 id="payment-notice-title">이용이 중단되었어요<br />결제 방식을 확인해주세요</h2>
          <p className="lead">결제수단을 확인하거나 구독을 다시 시작하면 매장 QR 서비스를 계속 이용할 수 있어요.</p>
        </div>
        <Link className="btn" href={`/admin/stores/${id}/plan`}>구독 및 결제 확인</Link>
        <button className="btn ghost" type="button" onClick={() => setShowPaymentNotice(false)}>나중에 확인</button>
      </Dialog>

      <section className="panel stack launch-pad owner-action-panel" aria-labelledby="launch-title">
        <div>
          <h2 id="launch-title">이벤트 시작 3단계</h2>
          <p className="lead">기본 상품과 QR은 이미 준비되어 있습니다.</p>
        </div>
        <ol className="launch-steps">
          <li><b>1</b><span>상품과 확률 확인</span></li>
          <li><b>2</b><span>QR 안내물 저장</span></li>
          <li><b>3</b><span>테이블에 비치하고 시작</span></li>
        </ol>
        <div className="launch-actions">
          <Link className="btn" href={`/admin/stores/${id}/qr`}>QR 안내물 열기</Link>
          <Link className="btn secondary" href={`/admin/stores/${id}/prizes`}>상품 확인</Link>
        </div>
      </section>

      <section className="panel stack" aria-labelledby="example-title">
        <div>
          <h2 id="example-title">우리 업종은 이렇게 해요</h2>
          <p className="lead">업종을 고르면 잘 맞는 경품 3단계 구성을 상품 설정에 불러와요. 저장하기 전까지는 바뀌지 않아요.</p>
        </div>
        <ExampleSearch storeId={id} />
        <Link className="hint" href="/examples" target="_blank">업종별 예시 전체 보기</Link>
      </section>

      {(summary.isError || store.isError) && (
        <p className="error" role="alert">{errorMessage(summary.error ?? store.error)}</p>
      )}

      <section className="owner-status-section" aria-labelledby="operation-status-title">
        <div className="owner-section-heading">
          <h2 id="operation-status-title">운영 현황</h2>
          <p className="hint">참여와 쿠폰 사용 흐름을 한눈에 확인하세요.</p>
        </div>
        <dl className="stats owner-status-strip" aria-label="이벤트 운영 현황">
          <div><dt>오늘 참여</dt><dd>{summary.data?.todayPlays ?? "-"}</dd></div>
          <div><dt>전체 참여</dt><dd>{summary.data?.totalPlays ?? "-"}</dd></div>
          <div>
            <dt>쿠폰 사용률</dt>
            <dd>{redemptionRate === null ? "—" : `${redemptionRate}%`}</dd>
          </div>
        </dl>
      </section>

      {summary.data?.totalPlays === 0 && (
        <p className="notice">아직 참여가 없습니다. QR을 비치하면 첫 참여가 여기에 표시됩니다.</p>
      )}

      <RetrySettingsPanel storeId={id} />

      {store.data?.status === "ACTIVE" && !store.data.address && (
        <p className="notice" role="status">
          입점 키트(안내물·테이블 스티커)를 받을 매장 주소가 아직 없어요.{" "}
          <Link href={`/admin/stores/${id}/settings`}>매장 정보에서 주소 입력하기</Link>
        </p>
      )}

      {/* AI가 알아낸 것을 사장이 착지하는 화면에서 바로 보게 한다. 별도 메뉴로만 두면 열어보지 않는다. */}
      {aiOpen && <AiInsightCard storeId={id} />}
    </AdminFrame>
  );
}
