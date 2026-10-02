"use client";
import Link from "next/link";
import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import type { RetryResponse, RevealResponse } from "@/types/api";
import { YUT_LABEL, rankLabel } from "@/features/labels";
import { clearPlayed } from "@/features/game/played";
import { Dialog } from "@/features/ui/Dialog";

export default function Result(){
  const p=useParams(),playId=String(p.playId),token=String(p.storeToken);
  const router=useRouter(),qc=useQueryClient();
  const path=`/public/games/${encodeURIComponent(playId)}`;
  const q=useQuery({queryKey:["result",playId],queryFn:()=>api<RevealResponse>(`${path}/reveal`,{method:"POST"})});
  // 한판 더 팝업. Esc·배경 클릭은 고르지 않은 채 닫기만 한다(새로고침하면 10분 안에는 다시 뜬다).
  const [dismissed,setDismissed]=useState(false);
  const retry=useMutation({
    mutationFn:()=>api<RetryResponse>(`${path}/retry`,{method:"POST"}),
    onSuccess:(g)=>{
      // 같은 playId를 새 seed로 다시 던진다. "이미 던짐" 표시와 첫 결과 캐시를 지워야 던지기 화면이 결과로 튕기지 않는다.
      clearPlayed(playId);
      qc.removeQueries({queryKey:["result",playId]});
      router.replace(`/s/${token}/game?playId=${encodeURIComponent(g.playId)}&seed=${encodeURIComponent(g.animationSeed)}`);
    },
  });
  const keep=useMutation({
    mutationFn:()=>api<RevealResponse>(`${path}/keep`,{method:"POST"}),
    onSuccess:(d)=>qc.setQueryData(["result",playId],d),
  });
  if(q.isPending)return (
    <main className="screen">
      <div className="stack" aria-live="polite" aria-busy="true">
        <span className="visually-hidden">결과를 불러오는 중</span>
        <div className="skeleton" style={{height:80,width:160}}/>
        <div className="skeleton" style={{height:28,width:"75%"}}/>
        <div className="skeleton" style={{height:96}}/>
      </div>
    </main>
  );
  if(q.isError)return (
    <main className="screen">
      <h1>결과를 불러오지 못했어요</h1>
      <p className="error" role="alert">{errorMessage(q.error)}</p>
    </main>
  );
  return (
    <main className="screen has-bar">
      <nav className="steps" aria-label="참여 단계">
        <span>1 정보 입력</span>
        <i data-on="1" />
        <span>2 윷 던지기</span>
        <i data-on="1" />
        <b>3 쿠폰</b>
      </nav>

      <header className="stack">
        <p className="eyebrow">윷 결과</p>
        <p className="result-mark">{YUT_LABEL[q.data.yutResult]}</p>
        <p className="pill" data-tone="wood">{q.data.retried&&"한판 더 · "}{rankLabel(q.data.prizeRank)} 상품</p>
        <h1>{q.data.prize.name}</h1>
        {q.data.prize.description&&<p className="lead">{q.data.prize.description}</p>}
      </header>

      <section className="panel">
        <div className="list">
          <div className="list-item">
            <span className="lead">사용 가능일</span>
            <span className="name">{new Date(q.data.validFrom).toLocaleString("ko-KR")}</span>
          </div>
          <div className="list-item">
            <span className="lead">만료일</span>
            <span className="name">{new Date(q.data.expiresAt).toLocaleString("ko-KR")}</span>
          </div>
        </div>
      </section>

      <p className="lead">사용할 때 직원에게 보여 주세요. QR을 다시 스캔해도 쿠폰을 확인할 수 있어요.</p>

      <div className="actionbar">
        <div className="inner">
          <Link className="btn" href={`/s/${token}/coupon/${q.data.couponToken}`}>쿠폰 보기</Link>
        </div>
      </div>

      <Dialog open={q.data.retryAvailable===true&&!dismissed} onClose={()=>setDismissed(true)} labelledBy="retry-title">
        <div className="stack">
          <h2 id="retry-title">한판 더?</h2>
          <p className="lead">
            지금 결과는 <b>{YUT_LABEL[q.data.yutResult]} · {rankLabel(q.data.prizeRank)} {q.data.prize.name}</b>예요.
            한 번 더 던지면 이 결과는 사라지고 새로 나온 결과로 쿠폰이 바뀌어요. 더 좋아질 수도, 아쉬워질 수도 있어요.
          </p>
          {(retry.isError||keep.isError)&&<p className="error" role="alert">{errorMessage(retry.error??keep.error)}</p>}
          <div className="sheet-actions">
            <button type="button" className="btn ghost" disabled={retry.isPending||keep.isPending} onClick={()=>keep.mutate()}>
              {keep.isPending?"저장 중":"이대로 만족해요!"}
            </button>
            <button type="button" className="btn" disabled={retry.isPending||keep.isPending} onClick={()=>retry.mutate()}>
              {retry.isPending?"준비 중":"한번 더 할래요!"}
            </button>
          </div>
        </div>
      </Dialog>
    </main>
  );
}
