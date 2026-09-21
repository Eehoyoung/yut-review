"use client";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import type { AdminMe, StoreStatus, StoreSummary } from "@/types/api";
import { STORE_STATUS_LABEL, STORE_STATUS_TONE } from "@/features/admin/labels";
import { BUSINESS_NUMBER_LENGTH, PHONE_LENGTH, formatBusinessNumber, onlyDigits } from "@/features/normalize";

type CreatedStore = { id: number; name: string; status: StoreStatus; approvalRequired: boolean };
const EMPTY_FORM = { name: "", businessNumber: "", phone: "" };

export default function Stores() {
  const qc = useQueryClient();
  const [form, setForm] = useState(EMPTY_FORM);
  const [adding, setAdding] = useState(false);
  const [tried, setTried] = useState(false);
  const [created, setCreated] = useState<CreatedStore>();

  const q = useQuery({ queryKey: ["admin-stores"], queryFn: () => api<StoreSummary[]>("/admin/stores") });
  // 운영자 메뉴는 역할이 확인된 뒤에만 나타난다. 링크 자체가 보이지 않아야 한다.
  const me = useQuery({ queryKey: ["admin-me"], queryFn: () => api<AdminMe>("/admin/me"), retry: false });
  const create = useMutation({
    mutationFn: () => api<CreatedStore>("/admin/stores", { method: "POST", body: JSON.stringify(form) }),
    onSuccess: (d) => {
      setCreated(d);
      setForm(EMPTY_FORM);
      setAdding(false);
      setTried(false);
      qc.invalidateQueries({ queryKey: ["admin-stores"] });
    },
  });

  // 무엇이 막고 있는지 한 문장으로. 버튼을 비활성화해 버리면 화면낭독기가 버튼을 찾지 못하고,
  // 눈으로 보는 사장도 세 칸 중 어디가 문제인지 알 수 없다.
  const blocked = !form.name.trim()
    ? { id: "store-name", message: "매장 상호명을 입력해 주세요." }
    : form.businessNumber.length !== BUSINESS_NUMBER_LENGTH
      ? { id: "store-biz", message: `사업자등록번호는 숫자 ${BUSINESS_NUMBER_LENGTH}자리로 입력해 주세요.` }
      : form.phone.length !== PHONE_LENGTH
        ? { id: "store-phone", message: `매장 연락처는 숫자 ${PHONE_LENGTH}자리로 입력해 주세요.` }
        : null;

  return (
    <main className="admin-shell">
      <header className="admin-head">
        <h1>내 매장</h1>
        <div className="sheet-actions">{me.data?.role === "SYSTEM_ADMIN" && (
          <Link className="btn ghost btn-inline" href="/admin/operator">운영자 콘솔</Link>
        )}<Link className="btn ghost btn-inline" href="/admin/marketing-consents">문자 수신 설정</Link>{!adding && (
          <button className="btn secondary btn-inline" onClick={() => setAdding(true)}>
            매장 추가
          </button>
        )}</div>
      </header>

      {created && (
        <p className="notice" role="status">
          {created.name} 등록을 신청했습니다.
          <br />운영자 승인 후 QR과 직원 PIN이 열립니다.
        </p>
      )}

      {q.isPending && (
        <div className="list" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">매장 목록을 불러오는 중</span>
          <div className="skeleton" style={{ height: 60 }} />
          <div className="skeleton" style={{ height: 60 }} />
        </div>
      )}
      {q.isError && (
        <p className="error" role="alert">
          {errorMessage(q.error)}
        </p>
      )}

      {q.data?.length === 0 && !adding && (
        <section className="panel stack">
          <h2>등록된 매장이 없어요</h2>
          <p className="lead">매장을 추가하면 운영자 승인 후 QR과 직원 PIN이 열립니다.</p>
          <button className="btn" onClick={() => setAdding(true)}>
            매장 추가
          </button>
        </section>
      )}

      {q.data && q.data.length > 0 && (
        <div className="list">
          {q.data.map((s) => {
            // 서버가 새 상태를 보내도 화면이 빈 칸이 되지 않게 ACTIVE로 떨어뜨린다.
            const status: StoreStatus = s.status && STORE_STATUS_LABEL[s.status] ? s.status : "ACTIVE";
            return (
              // 승인 대기·거부 매장도 링크는 살려 둔다. 막히는 이유는 대시보드가 상태 그대로 설명한다.
              <Link className="list-item store-link" key={s.id} href={`/admin/stores/${s.id}/dashboard`}>
                <span className="stack" style={{ gap: 2 }}>
                  <span className="name">{s.name}</span>
                  {s.businessNumber && <small className="hint">{formatBusinessNumber(s.businessNumber)}</small>}
                  {s.approvalNote && <small className="hint">사유: {s.approvalNote}</small>}
                </span>
                <span className="pill" data-tone={STORE_STATUS_TONE[status]}>
                  {STORE_STATUS_LABEL[status]}
                </span>
              </Link>
            );
          })}
        </div>
      )}

      {adding && (
        <form
          noValidate
          className="panel stack"
          onSubmit={(e: FormEvent) => {
            e.preventDefault();
            setTried(true);
            if (!blocked) {
              create.mutate();
              return;
            }
            document.getElementById(blocked.id)?.focus();
          }}
        >
          <h2>매장 추가</h2>
          <p className="lead">매장마다 다른 사업자등록번호가 필요합니다. 운영자 승인 후 QR과 직원 PIN이 열립니다.</p>
          <div className="field">
            <label htmlFor="store-name">매장 상호명</label>
            <input
              id="store-name"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              maxLength={100}
              autoComplete="organization"
              required
            />
          </div>
          <div className="field">
            <label htmlFor="store-biz">사업자등록번호</label>
            <input
              id="store-biz"
              inputMode="numeric"
              maxLength={BUSINESS_NUMBER_LENGTH}
              value={form.businessNumber}
              onChange={(e) => setForm({ ...form, businessNumber: onlyDigits(e.target.value, BUSINESS_NUMBER_LENGTH) })}
              required
            />
            <small className="hint">&lsquo;-&rsquo; 없이 숫자 10자리</small>
          </div>
          <div className="field">
            <label htmlFor="store-phone">매장 연락처</label>
            <input
              id="store-phone"
              type="tel"
              inputMode="numeric"
              maxLength={PHONE_LENGTH}
              value={form.phone}
              onChange={(e) => setForm({ ...form, phone: onlyDigits(e.target.value, PHONE_LENGTH) })}
              required
            />
            <small className="hint">010으로 시작하는 숫자 11자리</small>
          </div>
          {create.isError && (
            <p className="error" role="alert">
              {errorMessage(create.error)}
            </p>
          )}
          {tried && blocked && (
            <p className="notice" role="status">
              {blocked.message}
            </p>
          )}
          <div className="sheet-actions">
            <button
              type="button"
              className="btn ghost"
              onClick={() => {
                setAdding(false);
                setForm(EMPTY_FORM);
                setTried(false);
              }}
            >
              취소
            </button>
            <button className="btn" disabled={create.isPending}>
              {create.isPending ? "등록 중" : "매장 등록"}
            </button>
          </div>
        </form>
      )}
    </main>
  );
}
