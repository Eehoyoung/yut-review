"use client";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { ActivityPager,ActivityTable,type ActivityRow } from "@/features/admin/ActivityTable";
import { api,errorMessage } from "@/lib/api";
import type { PageData } from "@/types/api";
export default function Coupons() {
  const id = String(useParams().storeId);
  const [page, setPage] = useState(0);
  const q = useQuery({
    queryKey: ["admin-coupons", id, page],
    queryFn: () => api<PageData<ActivityRow>>(`/admin/stores/${id}/coupons?page=${page}&size=50`),
  });

  return (
    <AdminFrame title="쿠폰 내역">
      <header className="admin-page-intro">
        <div>
          <p className="eyebrow">혜택 사용</p>
          <h2>발급한 쿠폰의 상태를 살펴보세요</h2>
        </div>
        <p className="lead">최근 발급부터 50건씩 보여드립니다. 사용 여부와 발급 시각을 함께 확인할 수 있습니다.</p>
      </header>
      {q.isPending && <div className="skeleton" style={{ height: 240 }} aria-label="쿠폰 내역 불러오는 중" />}
      {q.isError && <p className="error" role="alert">{errorMessage(q.error)}</p>}
      {q.data && <ActivityTable rows={q.data.content} kind="coupon" />}
      <ActivityPager page={page} totalPages={q.data?.totalPages ?? 0} onChange={setPage} />
    </AdminFrame>
  );
}
