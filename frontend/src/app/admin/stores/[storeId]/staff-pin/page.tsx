"use client";
import { useState } from "react";
import { useParams } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api,errorMessage } from "@/lib/api";
export default function StaffPin(){const id=String(useParams().storeId),[pin,setPin]=useState("");const m=useMutation({mutationFn:()=>api<{pin:string}>(`/admin/stores/${id}/staff-pin/regenerate`,{method:"POST"}),onSuccess:d=>setPin(d.pin)});return <AdminFrame title="직원 PIN"><div className="panel stack"><p className="lead">PIN은 재발급 직후 한 번만 표시됩니다. 안전한 곳에 전달하고 화면을 닫아주세요.</p>{pin&&<><p className="pin-readout" aria-label="새 직원 PIN">{pin}</p><p className="notice">이 번호를 지금 기록하세요. 새로고침하면 다시 볼 수 없습니다.</p></>}{m.isError&&<p className="error">{errorMessage(m.error)}</p>}<button className="btn secondary" disabled={m.isPending} onClick={()=>confirm("기존 직원 PIN은 즉시 사용할 수 없게 됩니다. 계속할까요?")&&m.mutate()}>직원 PIN 재발급</button></div></AdminFrame>}
