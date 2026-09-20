"use client";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import { BUSINESS_NUMBER_LENGTH, PHONE_LENGTH, onlyDigits } from "@/features/normalize";

type SignUpResult = { storeId: number; storeName: string; staffPin: string; storeToken: string; posterReady: boolean };

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

/**
 * 조사는 앞 글자의 받침으로 갈린다('연락처를' vs '이메일을'). 라벨이 일곱 개라
 * 문장을 손으로 적으면 어느 하나는 반드시 어긋난다.
 */
const hasFinalConsonant = (word: string) => {
  const last = word.charCodeAt(word.length - 1);
  return last >= 0xac00 && last <= 0xd7a3 && (last - 0xac00) % 28 !== 0;
};

/**
 * 입력 검증. 첫 번째 문제와 그 칸의 id를 함께 돌려준다.
 *
 * id가 필요한 이유: 칸이 일곱 개라 안내 문구만 띄우면 정작 그 칸이 화면 밖에 있다.
 * 무엇이 문제인지 말하는 것과 거기로 데려다주는 것은 다른 일이다.
 */
function problem(form: Record<string, string>): { id: string; message: string } | null {
  for (const f of FIELDS) {
    const value = (form[f.key] ?? "").trim();
    if (!value) return { id: f.key, message: `${f.label}${hasFinalConsonant(f.label) ? "을" : "를"} 입력해 주세요.` };
    if (f.digits && value.length !== f.digits)
      return { id: f.key, message: `${f.label}${hasFinalConsonant(f.label) ? "은" : "는"} 숫자 ${f.digits}자리로 입력해 주세요.` };
  }
  return null;
}

export default function SignUp() {
  const [form, setForm] = useState<Record<string, string>>({});
  const [done, setDone] = useState<SignUpResult>();
  // 제출을 눌러 본 뒤에만 이유를 말한다. 폼을 열자마자, 또는 두 번째 칸을 치는 중에
  // 아직 오지도 않은 칸을 지적하면 잔소리가 된다.
  const [tried, setTried] = useState(false);
  const signUp = useMutation({
    mutationFn: () => api<SignUpResult>("/admin/auth/signup", { method: "POST", body: JSON.stringify(form) }),
    onSuccess: setDone,
  });

  if (done)
    return (
      <main className="screen">
        <p className="brand">윷리뷰</p>
        <h1>{done.storeName} 등록 완료</h1>
        <div className="panel stack">
          <p className="lead">직원 PIN은 지금 한 번만 표시됩니다. 매장 직원에게 안전하게 전달하세요.</p>
          <p className="pin-readout" aria-label="직원 PIN">
            {done.staffPin}
          </p>
          <p className="notice">
            A6 QR 안내물도 만들었습니다. 로그인 후 &lsquo;QR 안내물&rsquo;에서 저장하거나 공유하세요.
          </p>
        </div>
        <Link className="btn" href="/admin/login">
          로그인
        </Link>
      </main>
    );

  const blocked = problem(form);

  return (
    <main className="screen">
      <p className="brand">매장 관리자</p>
      <h1>매장 회원가입</h1>
      {/*
        noValidate: required 속성은 남겨 둔다(보조기술이 '필수'로 읽는다). 다만 브라우저 기본
        검증 풍선이 뜨면 submit 이벤트 자체가 오지 않아, 아래에서 한국어로 준비한 안내가
        영영 표시되지 않는다. 무엇이 막고 있는지는 한 목소리로만 말한다.
      */}
      <form
        noValidate
        className="panel stack"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          setTried(true);
          if (!blocked) {
            signUp.mutate();
            return;
          }
          document.getElementById(blocked.id)?.focus();
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
        {tried && blocked && (
          <p className="notice" role="status">
            {blocked.message}
          </p>
        )}
        {signUp.isError && (
          <p className="error" role="alert">
            {errorMessage(signUp.error)}
          </p>
        )}
        {/* 조건이 안 맞아도 버튼을 비활성화하지 않는다. disabled 버튼은 탭 순서에서 빠져
            화면낭독기가 발견조차 못 하고, 무엇이 막고 있는지 물어볼 방법도 사라진다. */}
        <button className="btn" disabled={signUp.isPending}>
          {signUp.isPending ? "등록 중" : "가입하고 매장 등록"}
        </button>
        <p className="lead">
          이미 계정이 있나요? <Link href="/admin/login">로그인</Link>
        </p>
      </form>
    </main>
  );
}
