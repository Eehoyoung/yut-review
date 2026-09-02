"use client";
import { FormEvent, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import { useSession } from "@/lib/session";
import type { CustomerState } from "@/types/api";

export default function Identify(){
 const token=String(useParams().storeToken),router=useRouter(),save=useSession(s=>s.setCustomer);
 const [name,setName]=useState(""),[phone,setPhone]=useState(""),[agreed,setAgreed]=useState(false);
 const mutation=useMutation({mutationFn:()=>api<CustomerState>(`/public/stores/${encodeURIComponent(token)}/customer-state`,{method:"POST",body:JSON.stringify({name,phone,privacyAgreed:agreed})}),onSuccess:data=>{save(token,name,phone);if(data.state==="HAS_ACTIVE_COUPON"&&data.couponToken)router.push(`/s/${token}/coupon/${data.couponToken}`);else if(data.state==="CAN_PLAY")router.push(`/s/${token}/staff-verify`);}});
 const submit=(e:FormEvent)=>{e.preventDefault();mutation.mutate()};
 return <main className="shell"><p className="brand">참여자 확인</p><h1>정보를 입력해주세요</h1><form className="card" onSubmit={submit}><div className="field"><label htmlFor="name">이름</label><input id="name" value={name} onChange={e=>setName(e.target.value)} required maxLength={50}/></div><div className="field"><label htmlFor="phone">휴대폰 번호</label><input id="phone" type="tel" inputMode="numeric" placeholder="01012345678" value={phone} onChange={e=>setPhone(e.target.value)} required/></div><label className="check"><input type="checkbox" checked={agreed} onChange={e=>setAgreed(e.target.checked)} required/><span>참여 확인 및 쿠폰 제공을 위한 개인정보 수집·이용에 동의합니다.</span></label>{mutation.data?.state==="COOLDOWN"&&<p className="notice">이미 최근에 참여하셨습니다. {mutation.data.nextPlayableDate}부터 다시 참여할 수 있어요.</p>}{mutation.isError&&<p className="error">{errorMessage(mutation.error)}</p>}<button className="btn" disabled={mutation.isPending}>{mutation.isPending?"확인 중...":"참여 가능 여부 확인"}</button></form></main>;
}

