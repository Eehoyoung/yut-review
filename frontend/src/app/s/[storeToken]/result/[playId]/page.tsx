"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import type { RevealResponse } from "@/types/api";
import { YUT_LABEL, rankLabel } from "@/features/labels";

export default function Result(){
  const p=useParams(),playId=String(p.playId),token=String(p.storeToken);
  const q=useQuery({queryKey:["result",playId],queryFn:()=>api<RevealResponse>(`/public/games/${encodeURIComponent(playId)}/reveal`,{method:"POST"})});
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
        <p className="eyebrow">던진 결과</p>
        <p className="result-mark">{YUT_LABEL[q.data.yutResult]}</p>
        <p className="pill" data-tone="wood">{rankLabel(q.data.prizeRank)} 상품</p>
        <h1>{q.data.prize.name}</h1>
        {q.data.prize.description&&<p className="lead">{q.data.prize.description}</p>}
      </header>

      <section className="panel">
        <div className="list">
          <div className="list-item">
            <span className="lead">사용 가능</span>
            <span className="name">{new Date(q.data.validFrom).toLocaleString("ko-KR")}</span>
          </div>
          <div className="list-item">
            <span className="lead">사용 기한</span>
            <span className="name">{new Date(q.data.expiresAt).toLocaleString("ko-KR")}</span>
          </div>
        </div>
      </section>

      <p className="lead">쿠폰은 사용할 때 직원이 확인합니다. 화면을 닫아도 QR로 다시 열 수 있어요.</p>

      <div className="actionbar">
        <div className="inner">
          <Link className="btn" href={`/s/${token}/coupon/${q.data.couponToken}`}>쿠폰 확인하기</Link>
        </div>
      </div>
    </main>
  );
}
