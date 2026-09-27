"use client";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { ActivityPager,ActivityTable,type ActivityRow } from "@/features/admin/ActivityTable";
import { api,errorMessage } from "@/lib/api";
import type { PageData } from "@/types/api";
export default function Plays() {
  const id = String(useParams().storeId);
  const [page, setPage] = useState(0);
  const q = useQuery({
    queryKey: ["plays", id, page],
    queryFn: () => api<PageData<ActivityRow>>(`/admin/stores/${id}/game-plays?page=${page}&size=50`),
  });

  return (
    <AdminFrame title="참여 내역">
      <header className="admin-page-intro">
        <div>
          <p className="eyebrow">고객 활동</p>
          <h2>누가 참여했는지 확인하세요</h2>
        </div>
        <p className="lead">최근 참여부터 50건씩 보여드립니다. 전화번호는 끝 네 자리만 표시합니다.</p>
      </header>
      {q.isPending && <div className="skeleton" style={{ height: 240 }} aria-label="참여 내역 불러오는 중" />}
      {q.isError && <p className="error" role="alert">{errorMessage(q.error)}</p>}
      {q.data && <ActivityTable rows={q.data.content} kind="play" />}
      <ActivityPager page={page} totalPages={q.data?.totalPages ?? 0} onChange={setPage} />
    </AdminFrame>
  );
}
