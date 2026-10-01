"use client";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ActivityPager } from "@/features/admin/ActivityTable";
import { PRINT_KIT_STATUS_LABEL, PRINT_KIT_STATUS_TONE } from "@/features/operator/labels";
import { OperatorFrame } from "@/features/operator/OperatorFrame";
import { formatBusinessNumber, formatPhone } from "@/features/normalize";
import { api, downloadWithAuth, errorMessage } from "@/lib/api";
import type { PrintKitPage, PrintKitStatus } from "@/types/api";

/**
 * 입점 키트(A6 안내물 4종 + 테이블 스티커 10장) 실물 발송 관리.
 *
 * 운영 중 매장만 나온다. 매장명이나 사업자등록번호로 찾아 인쇄소용 PDF를 받고, 진행 단계를 바꾼다.
 * PDF는 사장이 QR 화면에서 받는 파일과 같다(도련 3mm, 재단선 정보 포함).
 */
const STATUSES = Object.keys(PRINT_KIT_STATUS_LABEL) as PrintKitStatus[];

export default function PrintKitsPage() {
  const qc = useQueryClient();
  const [draft, setDraft] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<PrintKitStatus | "">("");
  const [page, setPage] = useState(0);
  const [flash, setFlash] = useState("");
  const [downloading, setDownloading] = useState<number>();
  const [downloadError, setDownloadError] = useState("");

  const kits = useQuery({
    queryKey: ["print-kits", query, status, page],
    queryFn: () =>
      api<PrintKitPage>(
        `/operator/print-kits?page=${page}&size=20${query ? `&q=${encodeURIComponent(query)}` : ""}${status ? `&status=${status}` : ""}`,
      ),
  });

  const change = useMutation({
    mutationFn: ({ storeId, next }: { storeId: number; next: PrintKitStatus; name: string }) =>
      api(`/operator/print-kits/${storeId}`, { method: "PUT", body: JSON.stringify({ status: next }) }),
    onSuccess: (_, vars) => {
      setFlash(`${vars.name}: ${PRINT_KIT_STATUS_LABEL[vars.next]}(으)로 바꿨습니다.`);
      qc.invalidateQueries({ queryKey: ["print-kits"] });
    },
  });

  const search = (event: FormEvent) => {
    event.preventDefault();
    setQuery(draft.trim());
    setPage(0);
  };

  const pdf = async (storeId: number, name: string) => {
    setDownloadError("");
    setDownloading(storeId);
    try {
      await downloadWithAuth(`/operator/print-kits/${storeId}/pdf`, `${name}_입점키트.pdf`);
    } catch (error) {
      setDownloadError(errorMessage(error));
    } finally {
      setDownloading(undefined);
    }
  };

  const counts = kits.data?.counts;
  const error = kits.error ?? change.error;

  return (
    <OperatorFrame title="입점 키트">
      <p className="lead">
        A6 안내물 4종(기본·이벤트·재방문·리뷰 부탁) 각 1매와 테이블 스티커(90×50mm) 10매를 인쇄해 보내는 곳이에요. PDF를
        인쇄소에 그대로 보내면 됩니다.
      </p>

      <dl className="stats" aria-label="입점 키트 진행 현황">
        {STATUSES.map((s) => (
          <div key={s}>
            <dt>{PRINT_KIT_STATUS_LABEL[s]}</dt>
            <dd>{counts ? counts[s].toLocaleString("ko-KR") : "-"}</dd>
          </div>
        ))}
      </dl>

      <form className="op-toolbar" onSubmit={search} role="search">
        <div className="field">
          <label htmlFor="print-kit-query">매장명 또는 사업자등록번호</label>
          <input
            id="print-kit-query"
            value={draft}
            maxLength={100}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="예: 소담 고깃집, 123-45-67890"
          />
        </div>
        <div className="field">
          <label htmlFor="print-kit-status">진행 단계</label>
          <select
            id="print-kit-status"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as PrintKitStatus | "");
              setPage(0);
            }}
          >
            <option value="">전체</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {PRINT_KIT_STATUS_LABEL[s]}
              </option>
            ))}
          </select>
        </div>
        <div className="sheet-actions">
          {query && (
            <button
              type="button"
              className="btn ghost btn-inline"
              onClick={() => {
                setDraft("");
                setQuery("");
                setPage(0);
              }}
            >
              검색 지우기
            </button>
          )}
          <button type="submit" className="btn btn-inline">
            검색
          </button>
        </div>
      </form>

      {flash && (
        <p className="success" role="status">
          {flash}
        </p>
      )}
      {(error || downloadError) && (
        <p className="error" role="alert">
          {downloadError || errorMessage(error)}
        </p>
      )}

      {kits.isPending && (
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">매장 목록을 불러오는 중</span>
          <div className="skeleton" style={{ height: 120 }} />
          <div className="skeleton" style={{ height: 120 }} />
        </div>
      )}

      {kits.data?.content.length === 0 && (
        <section className="panel stack">
          <h2>해당하는 매장이 없어요</h2>
          <p className="lead">
            {query || status ? "검색어나 진행 단계를 바꿔 보세요." : "운영 중인 매장이 생기면 여기에 표시됩니다."}
          </p>
        </section>
      )}

      <div className="op-cards">
        {kits.data?.content.map((k) => (
          <section className="panel stack op-card" key={k.storeId}>
            <div className="row">
              <h2>{k.name}</h2>
              <span className="pill" data-tone={PRINT_KIT_STATUS_TONE[k.status]}>
                {PRINT_KIT_STATUS_LABEL[k.status]}
              </span>
            </div>
            <div className="list">
              <div className="list-item">
                <span className="lead">사업자등록번호</span>
                <span className="name">{formatBusinessNumber(k.businessNumber)}</span>
              </div>
              <div className="list-item">
                <span className="lead">받는 분</span>
                <span className="name">
                  {k.representativeName || k.ownerName} · {formatPhone(k.ownerPhone || k.storePhone)}
                </span>
              </div>
              <div className="list-item">
                <span className="lead">매장 주소</span>
                {/* 가입 때 주소를 받지 않는다. 비어 있으면 발송 전에 사장에게 받아야 한다. */}
                <span className={k.address ? "name" : "error"}>{k.address || "주소 미입력 — 발송 전 확인 필요"}</span>
              </div>
              <div className="list-item">
                <span className="lead">매장 전화</span>
                <span className="name">{formatPhone(k.storePhone)}</span>
              </div>
              {k.statusUpdatedAt && (
                <div className="list-item">
                  <span className="lead">마지막 변경</span>
                  <span className="name">
                    {new Date(k.statusUpdatedAt).toLocaleString("ko-KR")} · {k.statusUpdatedBy}
                  </span>
                </div>
              )}
            </div>
            <div className="sheet-actions">
              <label className="visually-hidden" htmlFor={`kit-status-${k.storeId}`}>
                {k.name} 진행 단계
              </label>
              <select
                id={`kit-status-${k.storeId}`}
                value={k.status}
                disabled={change.isPending}
                onChange={(e) => change.mutate({ storeId: k.storeId, next: e.target.value as PrintKitStatus, name: k.name })}
              >
                {STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {PRINT_KIT_STATUS_LABEL[s]}
                  </option>
                ))}
              </select>
              <button
                type="button"
                className="btn btn-inline"
                disabled={downloading === k.storeId}
                onClick={() => pdf(k.storeId, k.name)}
              >
                {downloading === k.storeId ? "PDF 만드는 중" : "PDF 저장"}
              </button>
            </div>
          </section>
        ))}
      </div>

      {kits.data && <ActivityPager page={kits.data.page} totalPages={kits.data.totalPages} onChange={setPage} />}
    </OperatorFrame>
  );
}
