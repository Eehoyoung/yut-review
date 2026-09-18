"use client";
import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { opsApi, setOperatorToken } from "@/features/system/api";
import { consoleError } from "@/features/system/labels";
import type { OperatorEnrollment, OperatorLoginResult } from "@/types/api";

const CODE_LENGTH = 6;

/**
 * 운영자 로그인.
 *
 * 두 단계다. 비밀번호가 맞아야 2단계 인증 단계로 넘어가고, 인증 앱의 코드(또는 복구 코드)까지
 * 맞아야 토큰이 나온다. 처음 들어오는 계정은 등록을 먼저 하고, 등록을 마치면 복구 코드 열 개를
 * 그 자리에서 한 번만 보여 준다.
 *
 * 무엇이 틀렸는지는 서버가 구분해 주지 않는다. 화면도 마찬가지로 굳이 추측해 주지 않는다.
 */
export default function OperatorLogin() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [backupCode, setBackupCode] = useState("");
  const [useBackup, setUseBackup] = useState(false);
  const [enrollment, setEnrollment] = useState<OperatorEnrollment>();
  const [issuedCodes, setIssuedCodes] = useState<string[]>();

  const login = useMutation({
    mutationFn: () =>
      opsApi<OperatorLoginResult>("/auth/login", {
        method: "POST",
        body: JSON.stringify({
          email: email.trim(),
          password,
          code: useBackup ? "" : code.trim(),
          backupCode: useBackup ? backupCode.trim() : "",
        }),
      }),
    onSuccess: (data) => {
      if (data.status === "TOTP_ENROLLMENT_REQUIRED") {
        setEnrollment(data);
        setCode("");
        return;
      }
      setOperatorToken(data.accessToken);
      router.replace("/admin/system");
    },
  });

  const confirm = useMutation({
    mutationFn: () =>
      opsApi<{ status: string; backupCodes: string[] }>("/auth/totp/confirm", {
        method: "POST",
        body: JSON.stringify({ enrollmentToken: enrollment?.enrollmentToken ?? "", code: code.trim() }),
      }),
    onSuccess: (data) => {
      setEnrollment(undefined);
      setIssuedCodes(data.backupCodes);
      setCode("");
      setPassword("");
    },
  });

  if (enrollment)
    return (
      <main className="screen">
        <p className="brand">윷리뷰</p>
        <h1>2단계 인증 등록</h1>
        <form
          className="panel stack"
          onSubmit={(e: FormEvent) => {
            e.preventDefault();
            confirm.mutate();
          }}
        >
          <p className="lead">
            인증 앱(Google Authenticator, 1Password 등)으로 아래 QR을 찍고, 앱에 뜬 6자리 코드를 입력하세요.
          </p>
          {/* eslint-disable-next-line @next/next/no-img-element -- 서버가 만든 data: URL이라 next/image의 최적화 경로를 탈 수 없다. */}
          <img className="totp-qr" src={enrollment.qrImage} alt="인증 앱 등록용 QR 코드" width={240} height={240} />
          {/* QR을 못 읽는 상황(데스크톱 앱, 카메라 없음)을 위해 키 문자열도 함께 보여 준다. */}
          <p className="pin-readout wrap-anywhere">{enrollment.secret}</p>
          <div className="field">
            <label htmlFor="enroll-code">인증 코드</label>
            <input
              id="enroll-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={CODE_LENGTH}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, CODE_LENGTH))}
              required
            />
          </div>
          {confirm.isError && (
            <p className="error" role="alert">
              {consoleError(confirm.error)}
            </p>
          )}
          <button className="btn" disabled={confirm.isPending || code.length !== CODE_LENGTH}>
            {confirm.isPending ? "등록 중" : "등록 완료"}
          </button>
          <p className="hint">이 키는 다시 볼 수 없습니다. 인증 앱에 등록한 뒤 화면을 닫으세요.</p>
        </form>
      </main>
    );

  if (issuedCodes)
    return (
      <main className="screen">
        <p className="brand">윷리뷰</p>
        <h1>복구 코드</h1>
        <section className="panel stack">
          <p className="lead">
            폰을 잃어버렸을 때 쓰는 코드입니다. 지금 인쇄하거나 비밀번호 관리자에 저장하세요.
            <b> 이 화면을 닫으면 다시 볼 수 없습니다.</b>
          </p>
          <ul className="backup-codes">
            {issuedCodes.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
          <p className="hint">한 코드는 한 번만 씁니다. 다 쓰기 전에 보안 화면에서 새로 발급하세요.</p>
          <button className="btn" onClick={() => setIssuedCodes(undefined)}>
            저장했습니다. 로그인하기
          </button>
        </section>
      </main>
    );

  return (
    <main className="screen">
      <p className="brand">윷리뷰</p>
      <h1>운영자 콘솔</h1>
      <form
        className="panel stack"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          login.mutate();
        }}
      >
        <div className="field">
          <label htmlFor="ops-email">이메일</label>
          <input
            id="ops-email"
            type="email"
            inputMode="email"
            autoComplete="username"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="ops-password">비밀번호</label>
          <input
            id="ops-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        {useBackup ? (
          <div className="field">
            <label htmlFor="ops-backup">복구 코드</label>
            <input
              id="ops-backup"
              autoComplete="one-time-code"
              maxLength={9}
              placeholder="ABCD-EFGH"
              value={backupCode}
              onChange={(e) => setBackupCode(e.target.value.toUpperCase())}
              required
            />
            <small className="hint">등록할 때 받은 코드 중 아직 쓰지 않은 것</small>
          </div>
        ) : (
          <div className="field">
            <label htmlFor="ops-code">인증 코드</label>
            <input
              id="ops-code"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={CODE_LENGTH}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, CODE_LENGTH))}
            />
            <small className="hint">인증 앱의 6자리 코드</small>
          </div>
        )}
        {login.isError && (
          <p className="error" role="alert">
            {consoleError(login.error)}
          </p>
        )}
        <button className="btn" disabled={login.isPending}>
          {login.isPending ? "확인 중" : "로그인"}
        </button>
        <button
          type="button"
          className="btn ghost"
          onClick={() => {
            setUseBackup(!useBackup);
            setCode("");
            setBackupCode("");
          }}
        >
          {useBackup ? "인증 앱 코드로 로그인" : "폰이 없나요? 복구 코드 사용"}
        </button>
      </form>
    </main>
  );
}
