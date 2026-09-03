"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import type { StoreSummary } from "@/types/api";
import { oddsLabel, rankLabel } from "@/features/labels";

export default function StoreLanding(){
  const token=String(useParams().storeToken);
  const store=useQuery({queryKey:["store",token],queryFn:()=>api<StoreSummary>(`/public/stores/by-token/${encodeURIComponent(token)}`)});
  if(store.isPending)return (
    <main className="screen">
      <div className="stack" aria-live="polite" aria-busy="true">
        <span className="visually-hidden">매장 정보를 불러오는 중</span>
        <div className="skeleton" style={{height:14,width:120}}/>
        <div className="skeleton" style={{height:34,width:"70%"}}/>
        <div className="skeleton" style={{height:64}}/>
      </div>
    </main>
  );
  if(store.isError)return (
    <main className="screen">
      <h1>매장을 열 수 없어요</h1>
      <p className="error" role="alert">{errorMessage(store.error)}</p>
      <p className="lead">테이블의 QR을 다시 스캔하거나 직원에게 알려주세요.</p>
    </main>
  );
  return (
    <main className="screen has-bar">
      <header className="stack">
        <p className="brand">윷 한 판, 오늘의 상품</p>
        <h1>{store.data.name}</h1>
        <p className="lead">네이버 플레이스에 솔직한 방문 경험을 남긴 뒤 직원에게 작성 여부를 확인받아 주세요.</p>
      </header>

      <section className="panel" aria-labelledby="prize-heading">
        <div className="row" style={{marginBottom:"var(--s3)"}}>
          <h2 id="prize-heading">오늘의 윷 상품</h2>
          <span className="pill" data-tone="wood">도개걸윷모</span>
        </div>
        <div className="list">
          {store.data.prizes?.length?store.data.prizes.map(p=>(
            <div className="list-item" key={p.rank}>
              <span className="name">{rankLabel(p.rank)} {p.name}</span>
              <span className="pill">{oddsLabel(p.odds)}</span>
            </div>
          )):<p className="lead">윷을 던져 나온 결과에 따라 매장 상품을 드려요.</p>}
        </div>
      </section>

      <div className="actionbar">
        <div className="inner">
          {store.data.naverPlaceUrl&&<a className="btn ghost" target="_blank" rel="noreferrer" href={store.data.naverPlaceUrl}>네이버 플레이스에서 리뷰 쓰기</a>}
          <Link className="btn" href={`/s/${token}/identify`}>참여하기</Link>
        </div>
      </div>
    </main>
  );
}
