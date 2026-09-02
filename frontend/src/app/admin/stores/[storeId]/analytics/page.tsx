"use client";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api,errorMessage } from "@/lib/api";
import { YUT_LABEL, labelOf } from "@/features/labels";
type Analytics={totalPlays:number;issuedCoupons:number;redeemedCoupons:number;results?:Record<string,number>};
export default function AnalyticsPage(){const id=String(useParams().storeId),q=useQuery({queryKey:["analytics",id],queryFn:()=>api<Analytics>(`/admin/stores/${id}/analytics/summary`)}),couponTotal=(q.data?.issuedCoupons??0)+(q.data?.redeemedCoupons??0),rate=couponTotal?Math.round((q.data?.redeemedCoupons??0)/couponTotal*100):0;return <AdminFrame title="통계">{q.isError&&<p className="error">{errorMessage(q.error)}</p>}<div className="grid"><div className="card"><p className="lead">전체 참여</p><h2>{q.data?.totalPlays??"-"}</h2></div><div className="card"><p className="lead">쿠폰 사용률</p><h2>{q.data?`${rate}%`:"-"}</h2></div></div><div className="card"><h2>결과별 참여</h2>{Object.entries(q.data?.results??{}).map(([name,count])=><div className="row" key={name}><span>{labelOf(YUT_LABEL,name)}</span><b>{count}</b></div>)}</div></AdminFrame>}
