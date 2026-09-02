"use client";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { ActivityTable,type ActivityRow } from "@/features/admin/ActivityTable";
import { api,errorMessage } from "@/lib/api";
export default function Coupons(){const id=String(useParams().storeId),q=useQuery({queryKey:["admin-coupons",id],queryFn:()=>api<ActivityRow[]>(`/admin/stores/${id}/coupons`)});return <AdminFrame title="쿠폰 내역">{q.isError&&<p className="error">{errorMessage(q.error)}</p>}<ActivityTable rows={q.data??[]} kind="coupon"/></AdminFrame>}

