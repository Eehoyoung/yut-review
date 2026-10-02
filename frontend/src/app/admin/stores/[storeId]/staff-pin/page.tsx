"use client";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api, errorMessage } from "@/lib/api";

/** 보기만 한다. 재발급은 소담랩스 운영자만 한다 — 바꾸는 순간 직원 전원이 쓰던 PIN이 죽는다. */
export default function StaffPin() {
  const id = String(useParams().storeId);
  const current = useQuery({ queryKey: ["staff-pin", id], queryFn: () => api<{ pin: string | null }>(`/admin/stores/${id}/staff-pin`) });
  const pin = current.data?.pin;

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
            이전에 발급한 PIN은 화면에 표시할 수 없어요. 소담랩스 운영자에게 재발급을 요청하면 이후로는 여기서 계속 볼 수 있어요.
          </p>
        )}
        {current.isError && (
          <p className="error" role="alert">
            {errorMessage(current.error)}
          </p>
        )}
        <p className="hint">PIN 변경은 소담랩스 운영자에게 요청해 주세요. 바꾸면 기존 PIN은 즉시 사용할 수 없습니다.</p>
      </section>
    </AdminFrame>
  );
}
