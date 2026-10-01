"use client";
import { useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api, errorMessage } from "@/lib/api";
import { Dialog } from "@/features/ui/Dialog";

export default function StaffPin() {
  const id = String(useParams().storeId);
  const qc = useQueryClient();
  const current = useQuery({ queryKey: ["staff-pin", id], queryFn: () => api<{ pin: string | null }>(`/admin/stores/${id}/staff-pin`) });
  const pin = current.data?.pin;
  // 쿠폰 사용 처리와 같은 바텀시트를 쓴다. 같은 무게의 확인을 화면마다 다른 모양으로 물으면 안 된다.
  const [asking, setAsking] = useState(false);
  const m = useMutation({
    mutationFn: () => api<{ pin: string }>(`/admin/stores/${id}/staff-pin/regenerate`, { method: "POST" }),
    onSuccess: (d) => {
      qc.setQueryData(["staff-pin", id], d);
      setAsking(false);
    },
  });

  return (
    <AdminFrame title="직원 PIN">
      <section className="panel stack staff-pin-panel" aria-labelledby="staff-pin-heading">
        <div className="owner-section-heading">
          <h2 id="staff-pin-heading">쿠폰 사용용 직원 PIN</h2>
          <p className="lead">손님의 쿠폰을 사용할 때만 입력합니다.</p>
        </div>
        {pin && (
          <p className="pin-readout" aria-label="현재 직원 PIN">
            {pin}
          </p>
        )}
        {current.isSuccess && !pin && (
          <p className="notice" role="status">
            이전에 발급한 PIN은 화면에 표시할 수 없어요. 한 번 재발급하면 이후로는 여기서 계속 볼 수 있어요.
          </p>
        )}
        {current.isError && (
          <p className="error" role="alert">
            {errorMessage(current.error)}
          </p>
        )}
        {m.isError && (
          <p className="error" role="alert">
            {errorMessage(m.error)}
          </p>
        )}
        <button className="btn secondary" onClick={() => setAsking(true)}>
          직원 PIN 재발급
        </button>
        <p className="hint">재발급하면 기존 PIN은 즉시 사용할 수 없습니다.</p>
      </section>

      <Dialog open={asking} onClose={() => setAsking(false)} labelledBy="regen-title">
        <div className="stack">
          <h2 id="regen-title">PIN을 다시 발급할까요?</h2>
          <p className="lead">
            기존 PIN은 즉시 사용할 수 없습니다. 새 PIN을 직원에게 전달해 주세요.
          </p>
          {m.isError && (
            <p className="error" role="alert">
              {errorMessage(m.error)}
            </p>
          )}
          <div className="sheet-actions">
            <button type="button" className="btn ghost" onClick={() => setAsking(false)}>
              취소
            </button>
            <button type="button" className="btn" disabled={m.isPending} onClick={() => m.mutate()}>
              {m.isPending ? "발급 중" : "새 PIN 발급"}
            </button>
          </div>
        </div>
      </Dialog>
    </AdminFrame>
  );
}
