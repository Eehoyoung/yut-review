"use client";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { ActivityPager,ActivityTable,type ActivityRow } from "@/features/admin/ActivityTable";
import { api,errorMessage } from "@/lib/api";
import type { PageData } from "@/types/api";
export default function Plays(){const id=String(useParams().storeId),[page,setPage]=useState(0),q=useQuery({queryKey:["plays",id,page],queryFn:()=>api<PageData<ActivityRow>>(`/admin/stores/${id}/game-plays?page=${page}&size=50`)});return <AdminFrame title="참여 내역">{q.isError&&<p className="error">{errorMessage(q.error)}</p>}<ActivityTable rows={q.data?.content??[]} kind="play"/><ActivityPager page={page} totalPages={q.data?.totalPages??0} onChange={setPage}/></AdminFrame>}
