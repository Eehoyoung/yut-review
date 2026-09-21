"use client";
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { OperatorFrame } from "@/features/admin/OperatorFrame";
import { ResourceMonitor } from "@/features/admin/ResourceMonitor";
import { Dialog } from "@/features/ui/Dialog";
import { api, errorMessage } from "@/lib/api";

/**
 * 자원 현황과 위험 작업.
 *
 * 심사 화면에서 옮겨 왔다. 심사는 매일 보는 화면이고 자원 현황은 무언가 이상할 때 보는 화면이라,
 * 한 화면에 두면 매일 보는 쪽이 길어져서 정작 심사가 뒤로 밀린다.
 *
 * 해시 재계산을 여기에 두는 것은 같은 이유의 반대쪽이다. 1년에 한 번 쓸까 말까 한 버튼이
 * 매일 보는 화면에 있으면 언젠가 잘못 눌린다.
 */
export default function OperatorResourcesPage() {
  const [open, setOpen] = useState(false);
  const [flash, setFlash] = useState("");

  const rehash = useMutation({
    mutationFn: () =>
      api<{ scanned: number; rehashed: number }>("/admin/operator/phone-hash/rehash", { method: "POST" }),
    onSuccess: (d) => {
      setFlash(`${d.scanned}건을 확인해 ${d.rehashed}건을 다시 계산했습니다.`);
      setOpen(false);
    },
  });

  return (
    <OperatorFrame title="자원 현황">
      <ResourceMonitor />

      {flash && (
        <p className="success" role="status">
          {flash}
        </p>
      )}

      <section className="panel stack">
        <h2>위험 작업</h2>
        <p className="lead">
          전화번호 해시를 새 HMAC 키로 다시 계산합니다. 키 회전 중에만 1회 실행합니다.
        </p>
        <button type="button" className="btn secondary" onClick={() => setOpen(true)}>
          전화번호 해시 재계산
        </button>
      </section>

      <Dialog open={open} onClose={() => setOpen(false)} labelledBy="rehash-title">
        <div className="stack">
          <h2 id="rehash-title">전화번호 해시 재계산</h2>
          <p className="error">
            HMAC 키 회전 중에만 1회 실행하세요. 이전 키를 지운 뒤에 실행하면 기존 쿨타임 조회가 어긋납니다.
          </p>
          {rehash.isError && (
            <p className="error" role="alert">
              {errorMessage(rehash.error)}
            </p>
          )}
          <div className="sheet-actions">
            <button type="button" className="btn ghost" onClick={() => setOpen(false)}>
              취소
            </button>
            <button type="button" className="btn" disabled={rehash.isPending} onClick={() => rehash.mutate()}>
              {rehash.isPending ? "처리 중" : "실행"}
            </button>
          </div>
        </div>
      </Dialog>
    </OperatorFrame>
  );
}
