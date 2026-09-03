"use client";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import { BUSINESS_NUMBER_LENGTH, PHONE_LENGTH, onlyDigits } from "@/features/normalize";

type SignUpResult = { storeId: number; storeName: string; staffPin: string; storeToken: string };

type Field = {
  key: string;
  label: string;
  type: string;
  hint?: string;
  autoComplete?: string;
  /** 숫자만 받는 칸. 값이 이 길이를 넘으면 아예 입력되지 않는다. */
  digits?: number;
  inputMode?: "numeric" | "email" | "text";
};

const FIELDS: Field[] = [
  { key: "ownerName", label: "대표자 이름", type: "text", autoComplete: "name" },
  {
    key: "phone",
    label: "대표 연락처",
    type: "tel",
    hint: "010으로 시작하는 숫자 11자리",
    digits: PHONE_LENGTH,
    inputMode: "numeric",
    autoComplete: "tel",
  },
  { key: "email", label: "이메일", type: "email", hint: "로그인에 사용합니다.", inputMode: "email", autoComplete: "email" },
  { key: "password", label: "비밀번호", type: "password", hint: "영문과 숫자를 포함해 10자 이상", autoComplete: "new-password" },
  { key: "passwordConfirm", label: "비밀번호 확인", type: "password", autoComplete: "new-password" },
  { key: "storeName", label: "매장 상호명", type: "text", autoComplete: "organization" },
  {
    key: "businessNumber",
    label: "사업자등록번호",
    type: "text",
    hint: "'-' 없이 숫자 10자리",
    digits: BUSINESS_NUMBER_LENGTH,
    inputMode: "numeric",
  },
];

export default function SignUp() {
  const [form, setForm] = useState<Record<string, string>>({});
  const [done, setDone] = useState<SignUpResult>();
  const signUp = useMutation({
    mutationFn: () => api<SignUpResult>("/admin/auth/signup", { method: "POST", body: JSON.stringify(form) }),
    onSuccess: setDone,
  });

  if (done)
    return (
      <main className="screen">
        <p className="brand">가입 완료</p>
        <h1>{done.storeName} 등록됐어요</h1>
        <div className="panel stack">
          <p className="lead">직원 PIN은 지금 한 번만 표시됩니다. 매장 직원에게 안전하게 전달하세요.</p>
          <p className="pin-readout" aria-label="직원 PIN">
            {done.staffPin}
          </p>
          <p className="notice">
            PIN은 관리자 화면에서 다시 발급할 수 있습니다. 고객용 QR은 로그인 후 &lsquo;QR 관리&rsquo;에서 만드세요.
          </p>
        </div>
        <Link className="btn" href="/admin/login">
          로그인하러 가기
        </Link>
      </main>
    );

  const incomplete = FIELDS.some((f) => !(form[f.key] ?? "").trim() || (f.digits ? (form[f.key] ?? "").length !== f.digits : false));

  return (
    <main className="screen">
      <p className="brand">매장 관리자</p>
      <h1>매장 회원가입</h1>
      <form
        className="panel stack"
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
              inputMode={f.inputMode}
              autoComplete={f.autoComplete ?? "off"}
              maxLength={f.digits}
              value={form[f.key] ?? ""}
              onChange={(e) =>
                setForm({ ...form, [f.key]: f.digits ? onlyDigits(e.target.value, f.digits) : e.target.value })
              }
              required
            />
            {f.hint && <small className="hint">{f.hint}</small>}
          </div>
        ))}
        {signUp.isError && (
          <p className="error" role="alert">
            {errorMessage(signUp.error)}
          </p>
        )}
        <button className="btn" disabled={signUp.isPending || incomplete}>
          {signUp.isPending ? "등록 중..." : "가입하고 매장 만들기"}
        </button>
        <p className="lead">
          이미 계정이 있나요? <Link href="/admin/login">로그인</Link>
        </p>
      </form>
    </main>
  );
}
