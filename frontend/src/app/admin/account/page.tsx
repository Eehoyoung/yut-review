"use client";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { LogoutButton } from "@/features/admin/LogoutButton";
import { formatPhone, isPhone, onlyDigits, PHONE_LENGTH } from "@/features/normalize";
import { api, errorMessage } from "@/lib/api";

// types/api.ts의 AdminMe는 운영자 화면 작업과 같이 쓰는 파일이라 여기서 필요한 칸만 따로 적는다.
type Me = { email: string; name: string; phone: string };

/**
 * 내 정보(매장 관리자 계정). 이름·이메일은 보여 주기만 한다 — 이메일은 로그인 아이디, 이름은 사업자 확인 값이다.
 * 대표 연락처는 계정 찾기·비밀번호 재설정의 본인 확인 수단이라 바꿀 때 현재 비밀번호를 다시 묻는다. 서버도 같은 검사를 한다.
 */
export default function AccountPage() {
  const qc = useQueryClient();
  const me = useQuery({ queryKey: ["admin-me"], queryFn: () => api<Me>("/admin/me") });

  const [phone, setPhone] = useState("");
  const [phonePassword, setPhonePassword] = useState("");
  const [phoneTried, setPhoneTried] = useState(false);
  const changePhone = useMutation({
    mutationFn: () => api<{ phone: string }>("/admin/me/phone", { method: "PUT", body: JSON.stringify({ currentPassword: phonePassword, phone }) }),
    onSuccess: () => {
      setPhone("");
      setPhonePassword("");
      setPhoneTried(false);
      qc.invalidateQueries({ queryKey: ["admin-me"] });
    },
  });
  const phoneProblem = !isPhone(phone) ? "새 연락처는 010으로 시작하는 숫자 11자리로 입력해 주세요." : !phonePassword ? "현재 비밀번호를 입력해 주세요." : "";

  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [passwordTried, setPasswordTried] = useState(false);
  const changePassword = useMutation({
    mutationFn: () =>
      api("/admin/me/password", { method: "PUT", body: JSON.stringify({ currentPassword: current, newPassword: next, newPasswordConfirm: confirm }) }),
    onSuccess: () => {
      setCurrent("");
      setNext("");
      setConfirm("");
      setPasswordTried(false);
    },
  });
  // 가입과 같은 규칙. 서버(Inputs.password)가 다시 검사한다.
  const passwordProblem = !current
    ? "현재 비밀번호를 입력해 주세요."
    : next.length < 10 || !/[A-Za-z]/.test(next) || !/\d/.test(next)
      ? "새 비밀번호는 10자 이상, 영문과 숫자를 모두 넣어 주세요."
      : next !== confirm
        ? "새 비밀번호가 서로 다릅니다."
        : "";

  const submitPhone = (event: FormEvent) => {
    event.preventDefault();
    setPhoneTried(true);
    if (!phoneProblem) changePhone.mutate();
  };
  const submitPassword = (event: FormEvent) => {
    event.preventDefault();
    setPasswordTried(true);
    if (!passwordProblem) changePassword.mutate();
  };

  return (
    <main className="admin-shell">
      <header className="admin-head">
        <div>
          <p className="brand">매장 관리자 계정</p>
          <h1>내 정보</h1>
        </div>
        <div className="sheet-actions">
          <Link className="btn ghost btn-inline" href="/admin">
            내 매장
          </Link>
          <LogoutButton />
        </div>
      </header>

      {me.isError && (
        <p className="error" role="alert">
          {errorMessage(me.error)}
        </p>
      )}

      <section className="panel stack" aria-labelledby="account-fixed">
        <div className="owner-section-heading">
          <h2 id="account-fixed">계정</h2>
          <p className="hint">이름과 이메일(로그인 아이디)은 바꿀 수 없어요. 바꿔야 한다면 소담랩스에 문의해 주세요.</p>
        </div>
        <div className="list">
          <div className="list-item">
            <span className="lead">이름</span>
            <span className="name">{me.data?.name ?? "-"}</span>
          </div>
          <div className="list-item">
            <span className="lead">이메일</span>
            <span className="name">{me.data?.email ?? "-"}</span>
          </div>
          <div className="list-item">
            <span className="lead">대표 연락처</span>
            <span className="name">{me.data?.phone ? formatPhone(me.data.phone) : "-"}</span>
          </div>
        </div>
      </section>

      <form className="panel stack" onSubmit={submitPhone} noValidate aria-labelledby="phone-title">
        <div className="owner-section-heading">
          <h2 id="phone-title">대표 연락처 바꾸기</h2>
          <p className="hint">계정 찾기와 비밀번호 재설정에 쓰는 번호예요. 바꾸려면 현재 비밀번호가 필요해요.</p>
        </div>
        <div className="field">
          <label htmlFor="new-phone">새 연락처</label>
          <input
            id="new-phone"
            inputMode="numeric"
            autoComplete="tel"
            value={formatPhone(phone)}
            onChange={(event) => setPhone(onlyDigits(event.target.value, PHONE_LENGTH))}
            placeholder="010-1234-5678"
          />
        </div>
        <div className="field">
          <label htmlFor="phone-password">현재 비밀번호</label>
          <input
            id="phone-password"
            type="password"
            autoComplete="current-password"
            value={phonePassword}
            onChange={(event) => setPhonePassword(event.target.value)}
          />
        </div>
        {phoneTried && phoneProblem && (
          <p className="notice" role="status">
            {phoneProblem}
          </p>
        )}
        {changePhone.isError && (
          <p className="error" role="alert">
            {errorMessage(changePhone.error)}
          </p>
        )}
        {changePhone.isSuccess && (
          <p className="success" role="status">
            대표 연락처를 바꿨어요.
          </p>
        )}
        <button className="btn" disabled={changePhone.isPending}>
          {changePhone.isPending ? "바꾸는 중" : "연락처 바꾸기"}
        </button>
      </form>

      <form className="panel stack" onSubmit={submitPassword} noValidate aria-labelledby="password-title">
        <div className="owner-section-heading">
          <h2 id="password-title">비밀번호 바꾸기</h2>
          <p className="hint">10자 이상, 영문과 숫자를 모두 넣어 주세요.</p>
        </div>
        <div className="field">
          <label htmlFor="current-password">현재 비밀번호</label>
          <input
            id="current-password"
            type="password"
            autoComplete="current-password"
            value={current}
            onChange={(event) => setCurrent(event.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="new-password">새 비밀번호</label>
          <input id="new-password" type="password" autoComplete="new-password" value={next} onChange={(event) => setNext(event.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="new-password-confirm">새 비밀번호 확인</label>
          <input
            id="new-password-confirm"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
          />
        </div>
        {passwordTried && passwordProblem && (
          <p className="notice" role="status">
            {passwordProblem}
          </p>
        )}
        {changePassword.isError && (
          <p className="error" role="alert">
            {errorMessage(changePassword.error)}
          </p>
        )}
        {changePassword.isSuccess && (
          <p className="success" role="status">
            비밀번호를 바꿨어요. 다음 로그인부터 새 비밀번호를 쓰세요.
          </p>
        )}
        <button className="btn" disabled={changePassword.isPending}>
          {changePassword.isPending ? "바꾸는 중" : "비밀번호 바꾸기"}
        </button>
      </form>
    </main>
  );
}
