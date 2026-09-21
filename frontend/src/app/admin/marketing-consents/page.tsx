"use client";
import Link from "next/link";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import { MARKETING_SMS_VERSION } from "@/lib/legal";
import type { MarketingConsent, MarketingService } from "@/types/api";

export default function MarketingConsentsPage() {
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ["marketing-consents"], queryFn: () => api<MarketingConsent[]>("/admin/marketing-consents") });
  const update = useMutation({
    mutationFn: ({ service, agreed }: { service: MarketingService; agreed: boolean }) => api<MarketingConsent>("/admin/marketing-consents", { method: "PUT", body: JSON.stringify({ service, agreed, version: MARKETING_SMS_VERSION }) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["marketing-consents"] }),
  });
  return <main className="admin-shell">
    <header className="admin-head"><div><p className="brand">알림 설정</p><h1>광고성 문자 수신 동의</h1></div><Link className="btn ghost btn-inline" href="/admin">내 매장</Link></header>
    <section className="panel stack">
      <p className="lead">서비스별로 필요한 소식만 선택하세요. 동의를 거부하거나 철회해도 서비스 이용에는 영향이 없습니다.</p>
      {query.isPending && <p aria-live="polite">동의 상태를 불러오는 중입니다.</p>}
      {query.data?.map((item) => <label className="check" key={item.service}>
        <input type="checkbox" checked={item.agreed} disabled={update.isPending} onChange={(event) => update.mutate({ service: item.service, agreed: event.target.checked })} />
        <span><b>{item.serviceName}</b> 광고성 문자 수신 {item.agreed ? "동의 중" : "미동의"}{item.changedAt && <small className="hint"> — 마지막 변경 {new Date(item.changedAt).toLocaleString("ko-KR")}</small>}</span>
      </label>)}
      {(query.isError || update.isError) && <p className="error" role="alert">{errorMessage(query.error ?? update.error)}</p>}
      {update.isSuccess && <p className="notice" role="status">선택을 반영했습니다.</p>}
      <p><Link href="/legal/marketing">광고성 문자 수신동의 내용과 철회 방법</Link></p>
    </section>
  </main>;
}
