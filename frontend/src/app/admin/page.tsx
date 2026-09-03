"use client";
import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, errorMessage, isSystemAdmin } from "@/lib/api";
import type { StoreSummary } from "@/types/api";

type CreatedStore = { id: number; name: string; staffPin: string; storeToken: string };

export default function Stores() {
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [created, setCreated] = useState<CreatedStore>();
  // sessionStorage는 서버 렌더 시점에 없으므로 첫 페인트 뒤에 읽는다.
  const [canCreate, setCanCreate] = useState(false);
  useEffect(() => setCanCreate(isSystemAdmin()), []);

  const q = useQuery({ queryKey: ["admin-stores"], queryFn: () => api<StoreSummary[]>("/admin/stores") });
  const create = useMutation({
    mutationFn: () => api<CreatedStore>("/admin/stores", { method: "POST", body: JSON.stringify({ name }) }),
    onSuccess: (d) => {
      setCreated(d);
      setName("");
      qc.invalidateQueries({ queryKey: ["admin-stores"] });
    },
  });

  return (
    <main className="admin-shell">
      <header className="admin-head">
        <h1>내 매장</h1>
      </header>

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

      {q.data?.length === 0 && (
        <section className="panel stack">
          <h2>아직 연결된 매장이 없어요</h2>
          <p className="lead">
            {canCreate
              ? "아래에서 매장을 만들면 QR과 직원 PIN이 함께 발급됩니다."
              : "회원가입할 때 만든 매장이 여기에 표시됩니다. 목록이 비어 있다면 다른 계정으로 로그인했을 수 있어요."}
          </p>
          {!canCreate && (
            <Link className="btn secondary" href="/admin/login">
              다른 계정으로 로그인
            </Link>
          )}
        </section>
      )}

      {q.data && q.data.length > 0 && (
        <div className="list">
          {q.data.map((s) => (
            <Link className="list-item store-link" key={s.id} href={`/admin/stores/${s.id}/dashboard`}>
              <span className="name">{s.name}</span>
              <span className="pill" data-tone={s.active === false ? "off" : "ok"}>
                {s.active === false ? "운영 중지" : "운영 중"}
              </span>
            </Link>
          ))}
        </div>
      )}

      {canCreate && (
        <form
          className="panel stack"
          onSubmit={(e: FormEvent) => {
            e.preventDefault();
            create.mutate();
          }}
        >
          <h2>매장 추가</h2>
          <div className="field">
            <label htmlFor="store-name">새 매장명</label>
            <input id="store-name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} />
          </div>
          {create.isError && (
            <p className="error" role="alert">
              {errorMessage(create.error)}
            </p>
          )}
          {created && (
            <p className="notice" role="status">
              {created.name} 생성 완료 · 최초 PIN <b>{created.staffPin}</b>
              <br />
              이 PIN은 지금만 안전하게 기록하세요.
            </p>
          )}
          <button className="btn" disabled={create.isPending}>
            {create.isPending ? "생성 중..." : "매장 생성"}
          </button>
        </form>
      )}
    </main>
  );
}
