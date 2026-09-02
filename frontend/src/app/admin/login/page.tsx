"use client";
import Link from "next/link";
import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { api, errorMessage, setAdminToken } from "@/lib/api";
export default function Login(){const router=useRouter(),[loginId,setLoginId]=useState(""),[password,setPassword]=useState("");const m=useMutation({mutationFn:()=>api<{accessToken:string}>("/admin/auth/login",{method:"POST",body:JSON.stringify({loginId,password})}),onSuccess:data=>{setAdminToken(data.accessToken);router.replace("/admin")}});return <main className="screen"><p className="brand">STORE ADMIN</p><h1>매장 관리자 로그인</h1><form className="panel stack" onSubmit={(e:FormEvent)=>{e.preventDefault();m.mutate()}}><div className="field"><label htmlFor="loginId">아이디 또는 이메일</label><input id="loginId" autoComplete="username" value={loginId} onChange={e=>setLoginId(e.target.value)} required/></div><div className="field"><label htmlFor="password">비밀번호</label><input id="password" type="password" autoComplete="current-password" value={password} onChange={e=>setPassword(e.target.value)} required/></div>{m.isError&&<p className="error">{errorMessage(m.error)}</p>}<button className="btn" disabled={m.isPending}>{m.isPending?"로그인 중...":"로그인"}</button><p className="lead">매장이 처음이신가요? <Link href="/admin/signup">매장 회원가입</Link></p></form></main>}
