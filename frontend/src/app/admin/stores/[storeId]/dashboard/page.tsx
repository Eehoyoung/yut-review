"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { Dialog } from "@/features/ui/Dialog";
import { ApiClientError, api, errorMessage } from "@/lib/api";
import { AiInsightCard } from "@/features/admin/AiCards";
import { STORE_STATUS_HINT, STORE_STATUS_LABEL, STORE_STATUS_TONE } from "@/features/admin/labels";
import type { AiStatus, StoreStatus, Summary } from "@/types/api";

type PosterBrandTheme = "SODAM" | "FOREST" | "PLUM";
type StoreDetail = { id: number; name: string; phone: string; address: string; naverPlaceUrl?: string; posterTagline?: string; posterBrandTheme?: PosterBrandTheme; brandingAvailable?: boolean; serviceSuspended?: boolean; status?: StoreStatus; approvalNote?: string };

const BRAND_THEMES: { value: PosterBrandTheme; label: string; description: string }[] = [
  { value: "SODAM", label: "소담 네이비", description: "차분한 네이비와 오렌지" },
  { value: "FOREST", label: "포레스트", description: "깊은 초록과 부드러운 민트" },
  { value: "PLUM", label: "플럼", description: "짙은 자주와 연한 분홍" },
];
const PAYMENT_RESTRICTION_CODES = new Set(["SUBSCRIPTION_PAYMENT_REQUIRED", "STORE_PAYMENT_REQUIRED"]);

export default function Dashboard() {
  const id = String(useParams().storeId);
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [tagline, setTagline] = useState("");
  const [brandTheme, setBrandTheme] = useState<PosterBrandTheme>("SODAM");
  const [showPaymentNotice, setShowPaymentNotice] = useState(false);
  const summary = useQuery({
    queryKey: ["analytics", id],
    queryFn: () => api<Summary>(`/admin/stores/${id}/analytics/summary`),
  });
  const store = useQuery({
    queryKey: ["store", id],
    queryFn: () => api<StoreDetail>(`/admin/stores/${id}`),
  });

  useEffect(() => {
    if (store.data) {
      setName(store.data.name);
      setUrl(store.data.naverPlaceUrl ?? "");
      setTagline(store.data.posterTagline ?? "");
      setBrandTheme(store.data.posterBrandTheme ?? "SODAM");
    }
  }, [store.data]);

  const save = useMutation({
    mutationFn: () =>
      api<StoreDetail>(`/admin/stores/${id}`, {
        method: "PUT",
        body: JSON.stringify({
          name: name.trim(),
          naverPlaceUrl: url.trim(),
          posterTagline: store.data?.brandingAvailable ? tagline.trim() : undefined,
          posterBrandTheme: store.data?.brandingAvailable ? brandTheme : undefined,
        }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["store", id] }),
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

      <section className="panel stack launch-pad" aria-labelledby="launch-title">
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

      {(summary.isError || store.isError) && (
        <p className="error" role="alert">{errorMessage(summary.error ?? store.error)}</p>
      )}

      <dl className="stats" aria-label="이벤트 운영 현황">
        <div><dt>오늘 참여</dt><dd>{summary.data?.todayPlays ?? "-"}</dd></div>
        <div><dt>전체 참여</dt><dd>{summary.data?.totalPlays ?? "-"}</dd></div>
        <div>
          <dt>쿠폰 사용률</dt>
          <dd>{redemptionRate === null ? "—" : `${redemptionRate}%`}</dd>
        </div>
      </dl>

      {summary.data?.totalPlays === 0 && (
        <p className="notice">아직 참여가 없습니다. QR을 비치하면 첫 참여가 여기에 표시됩니다.</p>
      )}

      <form
        className="panel stack"
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          save.mutate();
        }}
      >
        <h2>매장 기본 정보</h2>
        <div className="field">
          <label htmlFor="store-name">매장명</label>
          <input id="store-name" value={name} onChange={(event) => setName(event.target.value)} required />
        </div>
        <fieldset className="branding-fieldset" disabled={!store.data?.brandingAvailable}>
          <legend>PRO 안내물 브랜딩</legend>
          <p className="hint">
            {store.data?.brandingAvailable
              ? "선택한 팔레트와 문구는 새로 생성하는 QR 안내물에 적용됩니다."
              : "PRO 요금제에서 매장 팔레트와 안내 문구를 설정할 수 있습니다."}
          </p>
          <div className="brand-theme-grid" role="radiogroup" aria-label="안내물 팔레트">
            {BRAND_THEMES.map((theme) => (
              <label key={theme.value} className="brand-theme-option" data-theme={theme.value}>
                <input
                  type="radio"
                  name="posterBrandTheme"
                  value={theme.value}
                  checked={brandTheme === theme.value}
                  onChange={() => setBrandTheme(theme.value)}
                />
                <span className="brand-theme-swatch" aria-hidden="true" />
                <span><b>{theme.label}</b><small>{theme.description}</small></span>
              </label>
            ))}
          </div>
          <div className="field">
            <label htmlFor="poster-tagline">안내물 한 줄 문구</label>
            <input
              id="poster-tagline"
              value={tagline}
              onChange={(event) => setTagline(event.target.value)}
              maxLength={60}
              placeholder="예: 오늘도 찾아주셔서 고맙습니다"
            />
            <small className="hint">비워 두면 안내물 종류별 기본 문구를 사용합니다. {tagline.length}/60</small>
          </div>
        </fieldset>
        <div className="field">
          <label htmlFor="naver-url">네이버 매장 링크 (선택)</label>
          <input
            id="naver-url"
            type="url"
            value={url}
            onChange={(event) => setUrl(event.target.value)}
            placeholder="https://map.naver.com/..."
          />
          <small className="hint">등록하면 고객 화면에 선택 링크가 표시됩니다.</small>
        </div>
        {save.isError && <p className="error" role="alert">{errorMessage(save.error)}</p>}
        {save.isSuccess && <p className="success" role="status">매장 정보를 저장했습니다.</p>}
        <button className="btn" disabled={save.isPending}>{save.isPending ? "저장 중" : "매장 정보 저장"}</button>
      </form>

      {/* AI가 알아낸 것을 사장이 착지하는 화면에서 바로 보게 한다. 별도 메뉴로만 두면 열어보지 않는다. */}
      {aiOpen && <AiInsightCard storeId={id} />}
    </AdminFrame>
  );
}
