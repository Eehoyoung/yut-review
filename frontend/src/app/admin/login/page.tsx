"use client";
import Image from "next/image";
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
    <main className="screen admin-login-screen">
      <div className="admin-login-brand">
        <Image src="/brand/sodam-wordmark.webp" width={1200} height={760} alt="소담" priority />
        <p>소담한판 매장 운영</p>
      </div>
      <h1>매장 관리자 로그인</h1>
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
        <button type="button" className="btn ghost" onClick={() => setRecoveryOpen(true)}>이메일·비밀번호 찾기</button>
      </form>
      <AccountRecoveryDialog open={recoveryOpen} onClose={() => setRecoveryOpen(false)} />
    </main>
  );
}
