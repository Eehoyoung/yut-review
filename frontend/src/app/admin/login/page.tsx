"use client";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { api, errorMessage, setAdminToken } from "@/lib/api";
import { AccountRecoveryDialog } from "@/features/admin/AccountRecoveryDialog";

export default function Login() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [recoveryOpen, setRecoveryOpen] = useState(false);
  const m = useMutation({
    mutationFn: () =>
      api<{ accessToken: string }>("/admin/auth/login", {
        method: "POST",
        body: JSON.stringify({ email, password }),
      }),
    onSuccess: (data) => {
      setAdminToken(data.accessToken);
      router.replace("/admin");
    },
  });

  return (
    <main className="screen">
      <p className="brand">소담한판</p>
      <h1>관리자 로그인</h1>
      <form
        className="panel stack"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          m.mutate();
        }}
      >
        <div className="field">
          <label htmlFor="email">이메일</label>
          <input
            id="email"
            type="email"
            inputMode="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="password">비밀번호</label>
          <input
            id="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        {m.isError && (
          <p className="error" role="alert">
            {errorMessage(m.error)}
          </p>
        )}
        <button className="btn" disabled={m.isPending}>
          {m.isPending ? "로그인 중" : "로그인"}
        </button>
        <p className="lead">
          처음이신가요? <Link href="/admin/signup">매장 등록</Link>
        </p>
        <p className="lead">
          시스템 운영자인가요? <Link href="/admin/operator/login">이메일 인증으로 로그인</Link>
        </p>
        <button type="button" className="btn ghost" onClick={() => setRecoveryOpen(true)}>이메일·비밀번호 찾기</button>
      </form>
      <AccountRecoveryDialog open={recoveryOpen} onClose={() => setRecoveryOpen(false)} />
    </main>
  );
}
