"use client";
import { FormEvent, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { Dialog } from "@/features/ui/Dialog";
import { api, errorMessage } from "@/lib/api";

type Purpose = "FIND_EMAIL" | "RESET_PASSWORD";
type Identity = { email: string; businessNumber: string; openingDate: string; representativeName: string; phone: string };
const initial: Identity = { email: "", businessNumber: "", openingDate: "", representativeName: "", phone: "" };

export function AccountRecoveryDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [purpose, setPurpose] = useState<Purpose>("FIND_EMAIL");
  const [identity, setIdentity] = useState(initial);
  const [challenge, setChallenge] = useState<{ challengeToken: string; maskedEmail: string }>();
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const request = useMutation({ mutationFn: () => api<{ challengeToken: string; maskedEmail: string }>("/admin/auth/recovery/request", { method: "POST", body: JSON.stringify({ ...identity, purpose }) }), onSuccess: setChallenge });
  const verify = useMutation({ mutationFn: () => api<{ email?: string; reset?: boolean }>("/admin/auth/recovery/verify", { method: "POST", body: JSON.stringify({ purpose, challengeToken: challenge?.challengeToken, code, password, passwordConfirm }) }) });
  const close = () => { setIdentity(initial);setChallenge(undefined);setCode("");setPassword("");setPasswordConfirm("");request.reset();verify.reset();onClose(); };
  const changePurpose = (next: Purpose) => { setPurpose(next);setChallenge(undefined);setCode("");request.reset();verify.reset(); };
  const field = (key: keyof Identity, label: string, type="text") => <div className="field"><label htmlFor={`recovery-${key}`}>{label}</label><input id={`recovery-${key}`} type={type} value={identity[key]} onChange={(e) => setIdentity({ ...identity, [key]: e.target.value })} required /></div>;
  const submit=(e:FormEvent)=>{e.preventDefault();if(challenge)verify.mutate();else request.mutate();};
  return <Dialog open={open} onClose={close} labelledBy="recovery-title"><form className="stack" onSubmit={submit}>
    <h2 id="recovery-title">이메일·비밀번호 찾기</h2>
    <div className="row"><button type="button" className={`btn ${purpose==="FIND_EMAIL"?"":"secondary"}`} onClick={()=>changePurpose("FIND_EMAIL")}>이메일 찾기</button><button type="button" className={`btn ${purpose==="RESET_PASSWORD"?"":"secondary"}`} onClick={()=>changePurpose("RESET_PASSWORD")}>비밀번호 재설정</button></div>
    {!challenge ? <>
      <p className="lead">가입 정보가 일치하면 등록 이메일로 6자리 인증번호를 보내드립니다.</p>
      {purpose==="RESET_PASSWORD"&&field("email","로그인 이메일","email")}
      {field("businessNumber","사업자등록번호")}{field("openingDate","개업일자 (YYYYMMDD)")}{field("representativeName","대표자명")}{field("phone","대표자 휴대전화","tel")}
    </> : <>
      <p className="notice">{challenge.maskedEmail}로 보낸 인증번호를 10분 안에 입력해 주세요.</p>
      <div className="field"><label htmlFor="recovery-code">인증번호</label><input id="recovery-code" inputMode="numeric" maxLength={6} value={code} onChange={(e)=>setCode(e.target.value.replace(/\D/g,""))} required /></div>
      {purpose==="RESET_PASSWORD"&&<><div className="field"><label htmlFor="recovery-password">새 비밀번호</label><input id="recovery-password" type="password" autoComplete="new-password" value={password} onChange={(e)=>setPassword(e.target.value)} required /><small className="hint">영문과 숫자를 포함해 10자 이상</small></div><div className="field"><label htmlFor="recovery-password-confirm">새 비밀번호 확인</label><input id="recovery-password-confirm" type="password" value={passwordConfirm} onChange={(e)=>setPasswordConfirm(e.target.value)} required /></div></>}
    </>}
    {(request.isError||verify.isError)&&<p className="error" role="alert">{errorMessage(request.error??verify.error)}</p>}
    {verify.data?.email&&<p className="success" role="status">가입 이메일: <strong>{verify.data.email}</strong></p>}
    {verify.data?.reset&&<p className="success" role="status">비밀번호를 변경했습니다.</p>}
    {!verify.isSuccess&&<button className="btn" disabled={request.isPending||verify.isPending}>{request.isPending||verify.isPending?"처리 중":challenge?"인증하기":"인증번호 받기"}</button>}
    <button type="button" className="btn ghost" onClick={close}>{verify.isSuccess?"닫기":"취소"}</button>
  </form></Dialog>;
}
