"use client";
import { FormEvent, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import { useSession } from "@/lib/session";
import type { GameCreated } from "@/types/api";

export default function StaffVerify(){
 const token=String(useParams().storeToken),router=useRouter(),session=useSession(),[pin,setPin]=useState(""),idempotencyKey=useRef(crypto.randomUUID());
 const mutation=useMutation({mutationFn:async()=>{const verified=await api<{verificationToken:string}>(`/public/stores/${encodeURIComponent(token)}/staff-verify`,{method:"POST",body:JSON.stringify({pin})});session.setVerification(verified.verificationToken);const game=await api<GameCreated>("/public/games",{method:"POST",body:JSON.stringify({storeToken:token,name:session.name,phone:session.phone,staffVerificationToken:verified.verificationToken,idempotencyKey:idempotencyKey.current})});return game},onSuccess:g=>{session.setGame(g.playId,g.animationSeed);router.push(`/s/${token}/game?playId=${encodeURIComponent(g.playId)}&seed=${encodeURIComponent(g.animationSeed)}`)}});
 return <main className="shell"><p className="brand">직원 확인</p><h1>직원 확인이 필요합니다</h1><p className="lead">리뷰 작성 여부를 확인한 직원이 6자리 PIN을 입력해주세요.</p><form className="card" onSubmit={(e:FormEvent)=>{e.preventDefault();mutation.mutate()}}><div className="field"><label htmlFor="pin">직원 PIN</label><input id="pin" type="password" inputMode="numeric" pattern="[0-9]{6}" maxLength={6} autoComplete="off" value={pin} onChange={e=>setPin(e.target.value.replace(/\D/g,""))} required/></div>{!session.phone&&<p className="error">참여자 정보가 없습니다. 이전 화면부터 다시 진행해주세요.</p>}{mutation.isError&&<p className="error">{errorMessage(mutation.error)}</p>}<button className="btn" disabled={mutation.isPending||!session.phone||pin.length!==6}>{mutation.isPending?"게임 준비 중...":"확인하고 게임 시작"}</button></form></main>;
}
