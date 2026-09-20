"use client";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { ActivityPager,ActivityTable,type ActivityRow } from "@/features/admin/ActivityTable";
import { api,errorMessage } from "@/lib/api";
import type { PageData } from "@/types/api";
export default function Coupons(){const id=String(useParams().storeId),[page,setPage]=useState(0),q=useQuery({queryKey:["admin-coupons",id,page],queryFn:()=>api<PageData<ActivityRow>>(`/admin/stores/${id}/coupons?page=${page}&size=50`)});return <AdminFrame title="쿠폰 내역">{q.isError&&<p className="error">{errorMessage(q.error)}</p>}<ActivityTable rows={q.data?.content??[]} kind="coupon"/><ActivityPager page={page} totalPages={q.data?.totalPages??0} onChange={setPage}/></AdminFrame>}
