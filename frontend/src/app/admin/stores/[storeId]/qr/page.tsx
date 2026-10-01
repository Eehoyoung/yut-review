"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api, downloadWithAuth, errorMessage } from "@/lib/api";

type Qr = { token: string; status?: string };
type Poster = { blob: Blob; publicOrigin: string };
type Variant = "GAME" | "EVENT" | "REVISIT" | "REVIEW" | "STICKER";

// 문구는 서버가 그린다. 여기서는 사장이 어느 것을 고를지 알 수 있을 만큼만 적는다.
const variants: { value: Variant; label: string; hint: string }[] = [
  { value: "GAME", label: "기본", hint: "윷 한 판 던지고 쿠폰 받아 가세요. 언제 붙여도 좋은 기본 안내물이에요." },
  { value: "EVENT", label: "이벤트", hint: "만나서 반가워요. 깜짝 선물 받아 가세요. 이벤트 기간에 눈에 띄게 붙이기 좋아요." },
  { value: "REVISIT", label: "재방문", hint: "오늘 즐거우셨나요? 계산대나 출입문처럼 다시 오실 분이 보는 자리에 어울려요." },
  { value: "REVIEW", label: "네이버 리뷰", hint: "네이버 리뷰 쓰고 윷 던져 상품 받기. 별점·내용과 관계없이 참여할 수 있다고 함께 적혀 있어요." },
  { value: "STICKER", label: "테이블 스티커", hint: "90×50mm 스티커 10장이 A4 한 장에 들어 있어요. 라벨지에 뽑거나 회색 선을 따라 잘라 테이블에 붙이세요." },
];

async function posterBlob(storeId: string, variant: Variant) {
  const token = sessionStorage.getItem("adminToken");
  const path = variant === "STICKER" ? "sticker-sheet" : `poster?variant=${variant}`;
  const response = await fetch(`/api/admin/stores/${storeId}/${path}`, {
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
  const [variant, setVariant] = useState<Variant>("GAME");
  const q = useQuery({ queryKey: ["qr", id], queryFn: () => api<Qr[]>(`/admin/stores/${id}/qr-codes`) });
  const poster = useQuery({ queryKey: ["poster", id, variant], queryFn: () => posterBlob(id, variant) });
  const regenerate = useMutation({
    mutationFn: () => api(`/admin/stores/${id}/poster/regenerate`, { method: "POST" }),
    onSuccess: async () => {
      await qc.invalidateQueries({ queryKey: ["poster", id] });
      setMessage("현재 주소로 안내물을 다시 만들었어요.");
    },
  });

  useEffect(() => {
    if (!poster.data) { setPreview(""); return; }
    const next = URL.createObjectURL(poster.data.blob);
    setPreview(next);
    return () => URL.revokeObjectURL(next);
  }, [poster.data]);

  const fileName = variant === "STICKER" ? `매장_${id}_테이블스티커_A4_10장.png` : `매장_${id}_${variant}_A6_QR.png`;
  const [pdfState, setPdfState] = useState<"idle" | "busy" | "error">("idle");
  const printPdf = async () => {
    setPdfState("busy");
    try {
      await downloadWithAuth(`/admin/stores/${id}/print-kit`, `매장_${id}_입점키트.pdf`);
      setPdfState("idle");
    } catch {
      setPdfState("error");
    }
  };

  const download = () => {
    if (!preview) return;
    const anchor = document.createElement("a");
    anchor.href = preview;
    anchor.download = fileName;
    anchor.click();
  };

  const share = async () => {
    if (!poster.data) return;
    setMessage("");
    const file = new File([poster.data.blob], fileName, { type: "image/png" });
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
      <section className="poster-workspace owner-poster-workspace" aria-labelledby="poster-workspace-title">
        <div className="poster-preview" aria-label="선택한 안내물 미리보기">
          {preview ? (
            <Image src={preview} width={620} height={874} unoptimized alt={variant === "STICKER" ? "테이블 스티커 10장이 들어간 A4 미리보기" : "매장명이 포함된 A6 QR 안내물 미리보기"} />
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
          <div className="owner-section-heading">
            <h2 id="poster-workspace-title">매장용 안내물</h2>
            <p className="lead">A6 안내물 네 가지와 테이블 스티커 모두 같은 QR이에요. 자리에 맞는 것을 골라 저장하거나 공유하세요.</p>
          </div>
          <fieldset className="poster-variants">
            <legend className="visually-hidden">안내물 종류</legend>
            {variants.map((item) => (
              <label key={item.value}>
                <input type="radio" name="poster-variant" value={item.value} checked={variant === item.value} onChange={() => { setVariant(item.value); setMessage(""); }} />
                <span>{item.label}</span>
              </label>
            ))}
          </fieldset>
          <p className="notice">{variants.find((item) => item.value === variant)?.hint}</p>
          <div className="poster-actions">
            <button className="btn" onClick={download} disabled={!preview}>이미지 저장</button>
            <button className="btn secondary" onClick={share} disabled={!poster.data}>공유</button>
          </div>
          <div className="notice stack">
            <p>인쇄소에 맡길 때는 PDF를 보내세요. 안내물 4종과 스티커가 재단 여백(3mm)까지 들어 있어요.</p>
            <button className="btn secondary" onClick={printPdf} disabled={pdfState === "busy"}>
              {pdfState === "busy" ? "PDF 만드는 중" : "인쇄용 PDF 저장"}
            </button>
            {pdfState === "error" && <p className="error" role="alert">PDF를 만들지 못했어요. 다시 시도해 주세요.</p>}
          </div>
          {message && <p className="success" role="status">{message}</p>}
          {regenerate.isError && <p className="error" role="alert">{errorMessage(regenerate.error)}</p>}
          <p className="notice">안내물 QR 주소: <span className="wrap-anywhere">{url}</span></p>
          {originChanged && <p className="error" role="alert">안내물의 접속 주소와 현재 접속 주소가 다릅니다. 소담랩스 운영자에게 문의해 주세요.</p>}
        </div>
      </section>

    </AdminFrame>
  );
}
