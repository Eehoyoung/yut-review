"use client";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";

type SignUpResult = { storeId: number; storeName: string; staffPin: string; storeToken: string };

const FIELDS = [
  { key: "ownerName", label: "대표자 이름", type: "text", hint: "" },
  { key: "phone", label: "대표 연락처", type: "tel", hint: "숫자만 입력해도 됩니다." },
  { key: "loginId", label: "아이디", type: "text", hint: "영문 소문자·숫자·_ 4~20자" },
  { key: "password", label: "비밀번호", type: "password", hint: "영문과 숫자를 포함해 10자 이상" },
  { key: "passwordConfirm", label: "비밀번호 확인", type: "password", hint: "" },
  { key: "email", label: "이메일", type: "email", hint: "" },
  { key: "storeName", label: "매장 상호명", type: "text", hint: "" },
  { key: "businessNumber", label: "사업자등록번호", type: "text", hint: "'-' 없이 10자리" },
] as const;

export default function SignUp() {
  const [form, setForm] = useState<Record<string, string>>({});
  const [done, setDone] = useState<SignUpResult>();
  const signUp = useMutation({
    mutationFn: () => api<SignUpResult>("/admin/auth/signup", { method: "POST", body: JSON.stringify(form) }),
    onSuccess: setDone,
  });

  if (done)
    return (
      <main className="shell">
        <p className="brand">가입 완료</p>
        <h1>{done.storeName} 등록됐어요</h1>
        <div className="card">
          <p className="lead">직원 PIN은 지금 한 번만 표시됩니다. 매장 직원에게 안전하게 전달하세요.</p>
          <div className="hero-mark" aria-label="직원 PIN">{done.staffPin}</div>
          <p className="notice">PIN은 관리자 화면에서 다시 발급할 수 있습니다. 고객용 QR은 로그인 후 &lsquo;QR 관리&rsquo;에서 만드세요.</p>
        </div>
        <Link className="btn" href="/admin/login">로그인하러 가기</Link>
      </main>
    );

  return (
    <main className="shell">
      <p className="brand">STORE ADMIN</p>
      <h1>매장 회원가입</h1>
      <form
        className="card"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          signUp.mutate();
        }}
      >
        {FIELDS.map((f) => (
          <div className="field" key={f.key}>
            <label htmlFor={f.key}>{f.label}</label>
            <input
              id={f.key}
              type={f.type}
              inputMode={f.type === "tel" ? "numeric" : undefined}
              autoComplete={f.key === "password" || f.key === "passwordConfirm" ? "new-password" : "off"}
              value={form[f.key] ?? ""}
              onChange={(e) => setForm({ ...form, [f.key]: e.target.value })}
              required
            />
            {f.hint && <small className="lead">{f.hint}</small>}
          </div>
        ))}
        {signUp.isError && <p className="error">{errorMessage(signUp.error)}</p>}
        <button className="btn" disabled={signUp.isPending}>{signUp.isPending ? "등록 중..." : "가입하고 매장 만들기"}</button>
        <p className="lead">이미 계정이 있나요? <Link href="/admin/login">로그인</Link></p>
      </form>
    </main>
  );
}
