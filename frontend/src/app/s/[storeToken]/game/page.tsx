"use client";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import YutGame from "@/components/yut/YutGame";
import { api } from "@/lib/api";
import type { RevealResponse } from "@/types/api";

export default function Game(){
 const token=String(useParams().storeToken),query=useSearchParams(),router=useRouter(),playId=query.get("playId")??"",seed=query.get("seed")??"";
 if(!playId||!seed)return <main className="shell"><p className="error">게임 정보가 없습니다. 참여 절차를 다시 시작해주세요.</p></main>;
 return <main style={{minHeight:"100dvh"}}><YutGame playId={playId} animationSeed={seed} reveal={()=>api<RevealResponse>(`/public/games/${encodeURIComponent(playId)}/reveal`,{method:"POST"})} onRevealed={()=>router.push(`/s/${token}/result/${playId}`)}/></main>;
}

