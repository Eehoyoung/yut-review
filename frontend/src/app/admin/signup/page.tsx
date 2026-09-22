"use client";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import { BUSINESS_NUMBER_LENGTH, PHONE_LENGTH, onlyDigits } from "@/features/normalize";
import { ADMIN_PRIVACY_VERSION, MARKETING_SMS_VERSION, TERMS_VERSION, marketingServices } from "@/lib/legal";
import type { StoreStatus } from "@/types/api";

/** 가입 결과 화면은 서버가 준 approvalRequired를 따른다. 승인제는 서버 설정으로 켜고 끈다. */
type SignUpResult = { storeId: number; storeName: string; status: StoreStatus; approvalRequired: boolean };

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
 * 국세청 진위확인이 켜져 있을 때만 붙는 칸.
 *
 * 켜고 끄는 것은 서버다(`app.business-verification.enabled`). 화면이 그 판단을 또 적으면
 * 서버와 어긋나므로 `/admin/auth/signup-requirements`가 알려 준 대로만 한다.
 *
 * 국세청은 번호·개업일자·대표자명 셋을 함께 본다. 번호 하나만 맞아서는 통과하지 못한다.
 */
const OPENING_DATE: Field = {
  key: "openingDate",
  label: "개업일자",
  type: "text",
  hint: "사업자등록증에 적힌 날짜. '-' 없이 8자리 (예: 20200101)",
  digits: 8,
  inputMode: "numeric",
};

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
function problem(fields: Field[], form: Record<string, string>, termsAgreed: boolean, privacyAgreed: boolean): { id: string; message: string } | null {
  for (const f of fields) {
    const value = (form[f.key] ?? "").trim();
    if (!value) return { id: f.key, message: `${f.label}${hasFinalConsonant(f.label) ? "을" : "를"} 입력해 주세요.` };
    if (f.digits && value.length !== f.digits)
      return { id: f.key, message: `${f.label}${hasFinalConsonant(f.label) ? "은" : "는"} 숫자 ${f.digits}자리로 입력해 주세요.` };
  }
  if (!termsAgreed) return { id: "termsAgreed", message: "서비스 이용약관에 동의해 주세요." };
  if (!privacyAgreed) return { id: "privacyAgreed", message: "개인정보 수집·이용에 동의해 주세요." };
  return null;
}

export default function SignUp() {
  const [form, setForm] = useState<Record<string, string>>({});
  const [done, setDone] = useState<SignUpResult>();
  // 제출을 눌러 본 뒤에만 이유를 말한다. 폼을 열자마자, 또는 두 번째 칸을 치는 중에
  // 아직 오지도 않은 칸을 지적하면 잔소리가 된다.
  const [tried, setTried] = useState(false);
  const [termsAgreed, setTermsAgreed] = useState(false);
  const [privacyAgreed, setPrivacyAgreed] = useState(false);
  const [marketing, setMarketing] = useState<Record<string, boolean>>({});

  // 실패해도 가입을 막지 않는다. 그 경우 개업일자를 묻지 않고 보내고, 검증이 켜져 있으면
  // 서버가 INVALID_OPENING_DATE로 되돌려 준다. 조회 한 번 실패가 가입 화면을 못 쓰게
  // 만드는 것보다 낫다.
  // 초대코드는 선택이라 FIELDS(전부 필수) 루프에 넣지 않는다. 넣으면 그 루프의 "모두 필수"가
  // 거짓이 되고, 다음 사람이 루프를 보고 잘못된 결론을 내린다.
  const [inviteCode, setInviteCode] = useState("");
  const normalizedInvite = inviteCode.toUpperCase().replace(/[^A-Z0-9]/g, "");
  const inviteCheck = useQuery({
    queryKey: ["invite-code", normalizedInvite],
    queryFn: () => api<{ exists: boolean }>(`/admin/auth/invite-code/${normalizedInvite}`),
    // 6자리가 차기 전에는 묻지 않는다. 타이핑 중간마다 "없는 코드"라고 말하면 잔소리가 된다.
    enabled: normalizedInvite.length === 6,
    retry: false,
    staleTime: 60_000,
  });
  /** 코드를 넣었는데 없는 것이 확인된 상태. 확인 중이거나 조회 실패는 여기 포함하지 않는다. */
  const inviteMissing = normalizedInvite.length > 0 && inviteCheck.data?.exists === false;

  const requirements = useQuery({
    queryKey: ["signup-requirements"],
    queryFn: () => api<{ businessVerification: boolean }>("/admin/auth/signup-requirements"),
    retry: false,
  });
  const fields = requirements.data?.businessVerification ? [...FIELDS, OPENING_DATE] : FIELDS;

  const signUp = useMutation({
    mutationFn: () => api<SignUpResult>("/admin/auth/signup", { method: "POST", body: JSON.stringify({ ...form, inviteCode: normalizedInvite, termsAgreed, privacyAgreed, termsVersion: TERMS_VERSION, privacyVersion: ADMIN_PRIVACY_VERSION, ...marketing, marketingVersion: MARKETING_SMS_VERSION }) }),
    onSuccess: setDone,
  });

  // 승인제는 서버 설정으로 켜고 끈다(app.store-approval-required). 화면이 어느 쪽인지 정하지 않고
  // 응답의 approvalRequired를 그대로 따른다. 여기에 설정을 또 적으면 서버와 어긋날 수 있다.
  if (done)
    return (
      <main className="screen">
        <p className="brand">소담한판</p>
        <h1>
          {done.storeName} {done.approvalRequired ? "신청 완료" : "등록 완료"}
        </h1>
        <div className="panel stack">
          {done.approvalRequired ? (
            <>
              <p className="lead">소담랩스 운영자가 사업자 정보를 확인하고 있습니다.</p>
              <p className="notice" role="status">
                승인되면 QR 안내물, 포스터, 직원 PIN이 열립니다.
              </p>
              <p className="hint">지금 바로 로그인해 심사 상태를 확인할 수 있습니다.</p>
            </>
          ) : (
            <>
              <p className="lead">바로 사용할 수 있습니다.</p>
              <p className="notice" role="status">
                로그인하면 QR 안내물, 포스터, 직원 PIN이 열립니다.
              </p>
            </>
          )}
        </div>
        <Link className="btn" href="/admin/login">
          로그인
        </Link>
      </main>
    );

  const fieldProblem = problem(fields, form, termsAgreed, privacyAgreed);
  // 코드가 틀리면 다음으로 넘어가지 못한다. 조용히 넘기면 오타를 친 사람이 추천이 반영된 줄 안다.
  const blocked =
    fieldProblem ??
    (normalizedInvite.length > 0 && normalizedInvite.length < 6
      ? { id: "inviteCode", message: "초대코드는 영문·숫자 6자리예요." }
      : inviteMissing
        ? { id: "inviteCode", message: "존재하지 않는 초대코드예요." }
        : inviteCheck.isFetching
          ? { id: "inviteCode", message: "초대코드를 확인하고 있어요. 잠시만요." }
          : null);

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
        {requirements.data?.businessVerification && (
          <p className="notice" role="status">
            국세청에 등록된 사업자등록번호·개업일자·대표자명이 모두 일치해야 가입됩니다.
          </p>
        )}

        {fields.map((f) => (
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

        <div className="field">
          <label htmlFor="inviteCode">초대코드 (선택)</label>
          <input
            id="inviteCode"
            type="text"
            inputMode="text"
            autoComplete="off"
            autoCapitalize="characters"
            maxLength={8}
            value={inviteCode}
            onChange={(e) => setInviteCode(e.target.value)}
            aria-invalid={inviteMissing || undefined}
            aria-describedby="inviteCode-hint"
          />
          <small className="hint" id="inviteCode-hint" aria-live="polite">
            {inviteMissing
              ? "존재하지 않는 초대코드예요."
              : normalizedInvite.length === 6 && inviteCheck.data?.exists
                ? "확인했어요."
                : "소개해 주신 분께 받은 영문·숫자 6자리. 없으면 비워 두세요."}
          </small>
        </div>

        <hr className="hair" />
        <label className="check">
          <input id="termsAgreed" type="checkbox" checked={termsAgreed} onChange={(e) => setTermsAgreed(e.target.checked)} required />
          <span><b>[필수]</b> <Link href="/legal/terms" target="_blank">서비스 이용약관</Link>에 동의합니다.</span>
        </label>
        <label className="check">
          <input id="privacyAgreed" type="checkbox" checked={privacyAgreed} onChange={(e) => setPrivacyAgreed(e.target.checked)} required />
          <span><b>[필수]</b> <Link href="/legal/privacy" target="_blank">개인정보 수집·이용 및 처리방침</Link>에 동의합니다.</span>
        </label>
        <div className="consent-summary">
          <p><b>수집:</b> 대표자 이름, 연락처, 이메일, 매장명, 사업자등록번호</p>
          <p><b>목적:</b> 회원가입, 로그인, 매장 운영과 고객 지원</p>
          <p><b>보유:</b> 서비스 이용 중 및 관계 법령상 보존 기간</p>
          <p>동의를 거부할 수 있으나 회원가입은 할 수 없습니다.</p>
        </div>
        <fieldset className="consent-summary">
          <legend><b>광고성 문자 수신 선택</b></legend>
          <p>소담랩스가 대표 연락처를 아래 서비스의 광고·혜택 안내에 이용하는 데 서비스별로 선택 동의합니다. 동의하지 않아도 가입과 서비스 이용에 불이익이 없습니다.</p>
          {marketingServices.map((item) => (
            <label className="check" key={item.service}>
              <input type="checkbox" checked={Boolean(marketing[item.key])} onChange={(e) => setMarketing({ ...marketing, [item.key]: e.target.checked })} />
              <span><b>[선택]</b> {item.name} 광고성 문자 수신 동의 <small className="hint">— {item.description}</small></span>
            </label>
          ))}
          <p><Link href="/legal/marketing" target="_blank">수집·이용 목적, 보유기간과 철회 방법 자세히 보기</Link></p>
        </fieldset>
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
