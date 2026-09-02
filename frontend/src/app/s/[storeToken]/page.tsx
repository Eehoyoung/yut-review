"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import type { StoreSummary } from "@/types/api";

export default function StoreLanding(){
  const token=String(useParams().storeToken);
  const store=useQuery({queryKey:["store",token],queryFn:()=>api<StoreSummary>(`/public/stores/by-token/${encodeURIComponent(token)}`)});
  if(store.isPending)return <main className="shell"><p>매장 정보를 불러오는 중...</p></main>;
  if(store.isError)return <main className="shell"><p className="error">{errorMessage(store.error)}</p></main>;
  return <main className="shell"><p className="brand">REVIEW YUT EVENT</p><h1>{store.data.name}</h1><p className="lead">네이버 플레이스에 솔직한 방문 경험을 남긴 뒤 직원에게 작성 여부를 확인받아 주세요.</p>
    <div className="card"><h2>오늘의 윷 상품</h2><div className="stack">{store.data.prizes?.map(p=><div className="row" key={p.tier}><span>{p.name}</span><span className="pill">{p.tier.replace("TIER_","")}등</span></div>)??<p className="lead">게임 결과에 따라 매장 상품을 드려요.</p>}</div></div>
    {store.data.naverPlaceUrl&&<a className="btn ghost" target="_blank" rel="noreferrer" href={store.data.naverPlaceUrl}>네이버 플레이스에서 리뷰 작성</a>}
    <div style={{height:10}}/><Link className="btn" href={`/s/${token}/identify`}>참여하기</Link></main>;
}

