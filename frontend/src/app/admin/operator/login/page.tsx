"use client";

import { FormEvent, useEffect, useState } from "react";
import Link from "next/link";
import { useMutation } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { api, errorMessage, setOperatorSession } from "@/lib/api";

type Challenge = { challengeToken: string; maskedEmail: string; expiresInSeconds: number };
type Session = { accessToken: string; expiresAt: string; expiresInSeconds: number };

export default function OperatorLoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [challenge, setChallenge] = useState<Challenge>();
  const [remaining, setRemaining] = useState(0);

  const request = useMutation({
    mutationFn: () => api<Challenge>("/admin/operator-auth/request", {
      method: "POST",
      body: JSON.stringify({ email: email.trim() }),
    }),
    onSuccess: (data) => {
      setChallenge(data);
      setRemaining(data.expiresInSeconds);
      setCode("");
    },
  });
  const verify = useMutation({
    mutationFn: () => api<Session>("/admin/operator-auth/verify", {
      method: "POST",
      body: JSON.stringify({ challengeToken: challenge?.challengeToken, code }),
    }),
    onSuccess: (data) => {
      setOperatorSession(data.accessToken, data.expiresAt);
      router.replace("/admin/operator");
    },
  });

  useEffect(() => {
    if (!challenge || remaining <= 0) return;
    const timer = window.setInterval(() => setRemaining((value) => Math.max(0, value - 1)), 1000);
    return () => window.clearInterval(timer);
  }, [challenge, remaining]);

  const submitEmail = (event: FormEvent) => {
    event.preventDefault();
    request.mutate();
  };
  const submitCode = (event: FormEvent) => {
    event.preventDefault();
    if (remaining > 0) verify.mutate();
  };

  return (
    <main className="screen">
      <p className="brand">소담랩스 운영자</p>
      <h1>시스템 운영자 로그인</h1>
      {!challenge ? (
        <form className="panel stack" onSubmit={submitEmail}>
          <p className="lead">등록된 운영자 이메일로 6자리 인증번호를 받습니다.</p>
          <div className="field">
            <label htmlFor="operator-email">운영자 이메일</label>
            <input id="operator-email" type="email" inputMode="email" autoComplete="email"
              value={email} onChange={(event) => setEmail(event.target.value)} required autoFocus />
          </div>
          {request.isError && <p className="error" role="alert">{errorMessage(request.error)}</p>}
          <button className="btn" disabled={request.isPending}>
            {request.isPending ? "보내는 중" : "인증번호 받기"}
          </button>
          <p className="hint">계정 존재 여부는 화면에 표시하지 않습니다.</p>
          <Link href="/admin/login">매장 관리자 로그인</Link>
        </form>
      ) : (
        <form className="panel stack" onSubmit={submitCode}>
          <p className="notice">{challenge.maskedEmail}로 인증번호를 보냈습니다.</p>
          <div className="field">
            <label htmlFor="operator-code">6자리 인증번호</label>
            <input id="operator-code" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}"
              maxLength={6} value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
              required autoFocus />
            <small className="hint" aria-live="polite">
              {remaining > 0 ? `유효시간 ${Math.floor(remaining / 60)}:${String(remaining % 60).padStart(2, "0")}` : "인증시간이 만료되었습니다."}
            </small>
          </div>
          {verify.isError && <p className="error" role="alert">{errorMessage(verify.error)}</p>}
          <button className="btn" disabled={verify.isPending || code.length !== 6 || remaining <= 0}>
            {verify.isPending ? "확인 중" : "운영자 로그인"}
          </button>
          <button type="button" className="btn ghost" onClick={() => { setChallenge(undefined); setRemaining(0); verify.reset(); }}>
            이메일 다시 입력
          </button>
        </form>
      )}
      <p className="hint">로그인 세션은 기본 10분이며 활동 중에도 자동 연장되지 않습니다.</p>
    </main>
  );
}
