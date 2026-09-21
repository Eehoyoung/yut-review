"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api, errorMessage } from "@/lib/api";
import { AiInsightCard } from "@/features/admin/AiCards";
import type { AiStatus, Summary } from "@/types/api";

type StoreDetail = { id: number; name: string; phone: string; address: string; naverPlaceUrl?: string };

export default function Dashboard() {
  const id = String(useParams().storeId);
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
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
    }
  }, [store.data]);

  const save = useMutation({
    mutationFn: () =>
      api<StoreDetail>(`/admin/stores/${id}`, {
        method: "PUT",
        body: JSON.stringify({ name: name.trim(), naverPlaceUrl: url.trim() }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["store", id] }),
  });
  const ai = useQuery({
    queryKey: ["ai-status", id],
    queryFn: () => api<AiStatus>(`/admin/stores/${id}/ai/status`),
    retry: false,
  });
  const aiOpen = ai.data?.features.some((feature) => feature.allowed) === true;
  const couponTotal = (summary.data?.issuedCoupons ?? 0) + (summary.data?.redeemedCoupons ?? 0);
  const redemptionRate = couponTotal
    ? Math.round(((summary.data?.redeemedCoupons ?? 0) / couponTotal) * 100)
    : null;

  return (
    <AdminFrame title="대시보드">
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
