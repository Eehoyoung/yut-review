"use client";
import { useEffect,useRef } from "react";
import { useParams } from "next/navigation";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import QRCode from "qrcode";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api,errorMessage } from "@/lib/api";
type Qr={token:string;status?:string};
export default function QrPage(){const id=String(useParams().storeId),canvas=useRef<HTMLCanvasElement>(null),qc=useQueryClient(),q=useQuery({queryKey:["qr",id],queryFn:()=>api<Qr[]>(`/admin/stores/${id}/qr-codes`)}),m=useMutation({mutationFn:()=>api<Qr>(`/admin/stores/${id}/qr-codes/regenerate`,{method:"POST"}),onSuccess:()=>qc.invalidateQueries({queryKey:["qr",id]})});const active=q.data?.find(x=>x.status==="ACTIVE")??q.data?.[0];const url=active&&typeof window!=="undefined"?`${window.location.origin}/s/${active.token}`:"";useEffect(()=>{if(canvas.current&&url)QRCode.toCanvas(canvas.current,url,{width:260,margin:1})},[url]);return <AdminFrame title="QR 관리">{q.isError&&<p className="error">{errorMessage(q.error)}</p>}<div className="card"><canvas ref={canvas}/><p className="lead" style={{wordBreak:"break-all"}}>{url}</p><p className="notice">터널 주소가 바뀌면 이 화면을 새 주소로 열어 QR을 다시 생성하세요.</p><button className="btn secondary" disabled={m.isPending} onClick={()=>confirm("기존 QR은 즉시 사용할 수 없게 됩니다. 재발급할까요?")&&m.mutate()}>QR 토큰 재발급</button></div></AdminFrame>}
