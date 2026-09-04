"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api, errorMessage } from "@/lib/api";
import { Dialog } from "@/features/ui/Dialog";

type Qr = { token: string; status?: string };
type Poster = { blob: Blob; publicOrigin: string };

async function posterBlob(storeId: string) {
  const token = sessionStorage.getItem("adminToken");
  const response = await fetch(`/api/admin/stores/${storeId}/poster`, {
    credentials: "include",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (response.status === 401) {
    sessionStorage.removeItem("adminToken");
    window.location.assign("/admin/login");
  }
  if (!response.ok) throw new Error("QR 안내물을 불러오지 못했어요. 다시 시도해 주세요.");
  return { blob: await response.blob(), publicOrigin: response.headers.get("X-Poster-Public-Origin") ?? "" } satisfies Poster;
}

export default function QrPage() {
  const id = String(useParams().storeId);
  const qc = useQueryClient();
  const [preview, setPreview] = useState("");
  const [message, setMessage] = useState("");
  const [askingRegenQr, setAskingRegenQr] = useState(false);
  const q = useQuery({ queryKey: ["qr", id], queryFn: () => api<Qr[]>(`/admin/stores/${id}/qr-codes`) });
  const poster = useQuery({ queryKey: ["poster", id], queryFn: () => posterBlob(id) });
  const regenerate = useMutation({
    mutationFn: () => api(`/admin/stores/${id}/poster/regenerate`, { method: "POST" }),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ["poster", id] });
      setMessage("현재 주소로 안내물을 다시 만들었어요.");
    },
  });
  const regenerateQr = useMutation({
    mutationFn: () => api<Qr>(`/admin/stores/${id}/qr-codes/regenerate`, { method: "POST" }),
    onSuccess: () => {
      setAskingRegenQr(false);
      qc.invalidateQueries({ queryKey: ["qr", id] });
      qc.invalidateQueries({ queryKey: ["poster", id] });
    },
  });

  useEffect(() => {
    if (!poster.data) return;
    const next = URL.createObjectURL(poster.data.blob);
    setPreview(next);
    return () => URL.revokeObjectURL(next);
  }, [poster.data]);

  const download = () => {
    if (!preview) return;
    const anchor = document.createElement("a");
    anchor.href = preview;
    anchor.download = `매장_${id}_A6_QR.png`;
    anchor.click();
  };

  const share = async () => {
    if (!poster.data) return;
    setMessage("");
    const file = new File([poster.data.blob], `매장_${id}_A6_QR.png`, { type: "image/png" });
    if (!navigator.share || !navigator.canShare?.({ files: [file] })) {
      download();
      setMessage("공유할 수 없어 이미지로 저장했어요.");
      return;
    }
    try {
      await navigator.share({ title: "매장 QR 안내", files: [file] });
      setMessage("공유 화면을 열었어요.");
    } catch (error) {
      if ((error as DOMException).name !== "AbortError") setMessage("공유하지 못했어요. 이미지로 저장해 주세요.");
    }
  };

  const active = q.data?.find((item) => item.status === "ACTIVE") ?? q.data?.[0];
  const url = active && poster.data?.publicOrigin ? `${poster.data.publicOrigin}/s/${active.token}` : "";
  const originChanged = Boolean(poster.data?.publicOrigin && typeof window !== "undefined" && poster.data.publicOrigin !== window.location.origin);

  return (
    <AdminFrame title="QR 안내물">
      {(q.isError || poster.isError) && <p className="error">{q.isError ? errorMessage(q.error) : "QR 안내물을 불러오지 못했어요. 다시 시도해 주세요."}</p>}
      <section className="poster-workspace">
        <div className="poster-preview">
          {preview ? (
            <Image src={preview} width={620} height={874} unoptimized alt="매장명이 포함된 A6 QR 안내물 미리보기" />
          ) : (
            // 부모가 place-items:center라 자식은 콘텐츠 너비로 줄어든다. 뼈대에 너비가 없으면
            // 폭 0으로 접혀 아무것도 안 보인다. 실제 안내물과 같은 A6 비율로 자리를 잡아 둔다.
            <div aria-live="polite" aria-busy="true" style={{ width: "min(100%, 320px)" }}>
              <span className="visually-hidden">A6 안내물을 불러오는 중</span>
              <div className="skeleton" style={{ width: "100%", aspectRatio: "105 / 148" }} />
            </div>
          )}
        </div>
        <div className="stack poster-controls">
          <div>
            <h2>매장용 A6 안내물</h2>
            <p className="lead">가입할 때 자동으로 만든 안내물입니다. 이미지로 저장하거나 공유하세요.</p>
          </div>
          <div className="poster-actions">
            <button className="btn" onClick={download} disabled={!preview}>이미지 저장</button>
            <button className="btn secondary" onClick={share} disabled={!poster.data}>공유</button>
          </div>
          {message && <p className="success" role="status">{message}</p>}
          {(regenerate.isError || regenerateQr.isError) && <p className="error" role="alert">{errorMessage(regenerate.error ?? regenerateQr.error)}</p>}
          <p className="notice">안내물 QR 주소: <span className="wrap-anywhere">{url}</span></p>
          {originChanged && <p className="error" role="alert">접속 주소가 바뀌었습니다. 안내물을 다시 만들어 주세요.</p>}
          <button className="btn ghost" disabled={regenerate.isPending} onClick={() => regenerate.mutate()}>
            {regenerate.isPending ? "다시 만드는 중" : "안내물 다시 만들기"}
          </button>
          <button className="btn ghost" disabled={regenerateQr.isPending} onClick={() => setAskingRegenQr(true)}>
            QR 토큰 재발급
          </button>
        </div>
      </section>

      <Dialog open={askingRegenQr} onClose={() => setAskingRegenQr(false)} labelledBy="qr-regen-title">
        <div className="stack">
          <h2 id="qr-regen-title">QR을 다시 발급할까요?</h2>
          {/* 제목이 이미 묻고 있다. 본문은 되묻는 대신 무엇을 감수해야 하는지를 말한다. */}
          <p className="lead">
            기존 QR은 즉시 사용할 수 없게 됩니다. 매장에 붙여 둔 안내물도 새 QR로 다시 출력해야 합니다.
          </p>
          <div className="sheet-actions">
            <button type="button" className="btn ghost" onClick={() => setAskingRegenQr(false)}>
              취소
            </button>
            <button
              type="button"
              className="btn"
              disabled={regenerateQr.isPending}
              onClick={() => regenerateQr.mutate()}
            >
              {regenerateQr.isPending ? "발급 중" : "새 QR 발급"}
            </button>
          </div>
        </div>
      </Dialog>
    </AdminFrame>
  );
}
