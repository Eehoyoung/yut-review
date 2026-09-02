"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import type { RevealResponse } from "@/types/api";
import { YUT_LABEL } from "@/features/labels";
export default function Result(){const p=useParams(),playId=String(p.playId),token=String(p.storeToken);const q=useQuery({queryKey:["result",playId],queryFn:()=>api<RevealResponse>(`/public/games/${encodeURIComponent(playId)}/reveal`,{method:"POST"})});if(q.isPending)return <main className="shell">결과를 불러오는 중...</main>;if(q.isError)return <main className="shell"><p className="error">{errorMessage(q.error)}</p></main>;return <main className="shell"><p className="brand">당첨 결과</p><div className="hero-mark">🎉</div><h1>{YUT_LABEL[q.data.yutResult]}! {q.data.prize.name}</h1><p className="lead">{q.data.prize.description}</p><div className="card"><p><b>사용 가능</b><br/>{new Date(q.data.validFrom).toLocaleString("ko-KR")}</p><p><b>사용 기한</b><br/>{new Date(q.data.expiresAt).toLocaleString("ko-KR")}</p></div><Link className="btn" href={`/s/${token}/coupon/${q.data.couponToken}`}>쿠폰 확인하기</Link></main>}

