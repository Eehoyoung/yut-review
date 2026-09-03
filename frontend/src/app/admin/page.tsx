"use client";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import type { StoreSummary } from "@/types/api";
import { BUSINESS_NUMBER_LENGTH, PHONE_LENGTH, formatBusinessNumber, onlyDigits } from "@/features/normalize";

type CreatedStore = { id: number; name: string; staffPin: string; storeToken: string };
const EMPTY_FORM = { name: "", businessNumber: "", phone: "" };

export default function Stores() {
  const qc = useQueryClient();
  const [form, setForm] = useState(EMPTY_FORM);
  const [adding, setAdding] = useState(false);
  const [created, setCreated] = useState<CreatedStore>();

  const q = useQuery({ queryKey: ["admin-stores"], queryFn: () => api<StoreSummary[]>("/admin/stores") });
  const create = useMutation({
    mutationFn: () => api<CreatedStore>("/admin/stores", { method: "POST", body: JSON.stringify(form) }),
    onSuccess: (d) => {
      setCreated(d);
      setForm(EMPTY_FORM);
      setAdding(false);
      qc.invalidateQueries({ queryKey: ["admin-stores"] });
    },
  });

  const incomplete =
    !form.name.trim() || form.businessNumber.length !== BUSINESS_NUMBER_LENGTH || form.phone.length !== PHONE_LENGTH;

  return (
    <main className="admin-shell">
      <header className="admin-head">
        <h1>내 매장</h1>
        {!adding && (
          <button className="btn secondary btn-inline" onClick={() => setAdding(true)}>
            매장 추가
          </button>
        )}
      </header>

      {created && (
        <p className="notice" role="status">
          {created.name} 등록 완료 · 최초 직원 PIN <b>{created.staffPin}</b>
          <br />이 번호는 지금만 표시됩니다. 안전한 곳에 기록하세요.
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
          <h2>아직 연결된 매장이 없어요</h2>
          <p className="lead">매장을 추가하면 고객용 QR과 직원 PIN이 함께 발급됩니다.</p>
          <button className="btn" onClick={() => setAdding(true)}>
            첫 매장 만들기
          </button>
        </section>
      )}

      {q.data && q.data.length > 0 && (
        <div className="list">
          {q.data.map((s) => (
            <Link className="list-item store-link" key={s.id} href={`/admin/stores/${s.id}/dashboard`}>
              <span className="stack" style={{ gap: 2 }}>
                <span className="name">{s.name}</span>
                {s.businessNumber && <small className="hint">{formatBusinessNumber(s.businessNumber)}</small>}
              </span>
              <span className="pill" data-tone={s.active === false ? "off" : "ok"}>
                {s.active === false ? "운영 중지" : "운영 중"}
              </span>
            </Link>
          ))}
        </div>
      )}

      {adding && (
        <form
          className="panel stack"
          onSubmit={(e: FormEvent) => {
            e.preventDefault();
            create.mutate();
          }}
        >
          <h2>매장 추가</h2>
          <p className="lead">사업자등록번호는 매장마다 달라야 합니다. 등록하면 QR과 직원 PIN이 새로 발급됩니다.</p>
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
          <div className="sheet-actions">
            <button
              type="button"
              className="btn ghost"
              onClick={() => {
                setAdding(false);
                setForm(EMPTY_FORM);
              }}
            >
              취소
            </button>
            <button className="btn" disabled={create.isPending || incomplete}>
              {create.isPending ? "등록 중..." : "매장 등록"}
            </button>
          </div>
        </form>
      )}
    </main>
  );
}
