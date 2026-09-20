"use client";
import { useState } from "react";
import { useParams } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api, errorMessage } from "@/lib/api";
import { Dialog } from "@/features/ui/Dialog";

export default function StaffPin() {
  const id = String(useParams().storeId);
  const [pin, setPin] = useState("");
  // 쿠폰 사용 처리와 같은 바텀시트를 쓴다. 같은 무게의 확인을 화면마다 다른 모양으로 물으면 안 된다.
  const [asking, setAsking] = useState(false);
  const m = useMutation({
    mutationFn: () => api<{ pin: string }>(`/admin/stores/${id}/staff-pin/regenerate`, { method: "POST" }),
    onSuccess: (d) => {
      setPin(d.pin);
      setAsking(false);
    },
  });

  return (
    <AdminFrame title="직원 PIN">
      <div className="panel stack">
        <p className="lead">새 PIN은 발급 직후 한 번만 표시됩니다.</p>
        {pin && (
          <>
            <p className="pin-readout" aria-label="새 직원 PIN">
              {pin}
            </p>
            <p className="notice" role="status">
              지금 기록하세요. 새로고침하면 다시 볼 수 없습니다.
            </p>
          </>
        )}
        {m.isError && (
          <p className="error" role="alert">
            {errorMessage(m.error)}
          </p>
        )}
        <button className="btn secondary" onClick={() => setAsking(true)}>
          직원 PIN 재발급
        </button>
      </div>

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
