"use client";
import { FormEvent, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import type { Coupon } from "@/types/api";
import { rankLabel } from "@/features/labels";
import { Dialog } from "@/features/ui/Dialog";

export default function CouponPage(){
  const token=String(useParams().couponToken),qc=useQueryClient(),[open,setOpen]=useState(false),[pin,setPin]=useState("");
  const q=useQuery({queryKey:["coupon",token],queryFn:()=>api<Coupon>(`/public/coupons/${encodeURIComponent(token)}`)});
  const redeem=useMutation({mutationFn:()=>api<Coupon>(`/public/coupons/${encodeURIComponent(token)}/redeem`,{method:"POST",body:JSON.stringify({pin})}),onSuccess:()=>{setOpen(false);setPin("");qc.invalidateQueries({queryKey:["coupon",token]})}});
  const closeSheet=()=>{setOpen(false);setPin("");redeem.reset();};
  if(q.isPending)return (
    <main className="screen">
      <div className="stack" aria-live="polite" aria-busy="true">
        <span className="visually-hidden">쿠폰을 불러오는 중</span>
        <div className="skeleton" style={{height:28,width:"60%"}}/>
        <div className="skeleton" style={{height:180}}/>
      </div>
    </main>
  );
  if(q.isError)return (
    <main className="screen">
      <h1>쿠폰을 열 수 없어요</h1>
      <p className="error" role="alert">{errorMessage(q.error)}</p>
    </main>
  );
  const c=q.data,name=c.prizeName??c.prize?.name??"당첨 상품",notYet=c.status==="ISSUED"&&new Date(c.validFrom)>new Date();
  return (
    <main className={c.status==="ISSUED"?"screen has-bar":"screen"}>
      <header className="stack">
        <p className="eyebrow">내 쿠폰</p>
        <h1>{name}</h1>
      </header>

      <section className="ticket">
        <div className="row">
          {c.prizeRank?<span className="pill" data-tone="wood">{rankLabel(c.prizeRank)}</span>:null}
          <span className="pill" data-tone={c.status==="REDEEMED"?"ok":notYet||c.status!=="ISSUED"?"off":"wood"}>
            {notYet?"아직 사용 불가":c.status==="ISSUED"?"사용 가능":c.status==="REDEEMED"?"사용 완료":c.status==="EXPIRED"?"기간 만료":"사용 취소"}
          </span>
        </div>
        {(c.prizeDescription??c.prize?.description)&&<p className="lead">{c.prizeDescription??c.prize?.description}</p>}
        <div className="ticket-perf" aria-hidden="true"/>
        <div className="list">
          <div className="list-item">
            <span className="lead">사용 가능일</span>
            <span className="name">{new Date(c.validFrom).toLocaleString("ko-KR")}</span>
          </div>
          <div className="list-item">
            <span className="lead">만료일</span>
            <span className="name">{new Date(c.expiresAt).toLocaleString("ko-KR")}</span>
          </div>
        </div>
      </section>

      {c.status==="ISSUED"&&(
        <p className="lead">
          {notYet?"사용 가능일이 되면 이 화면에서 직원이 사용 처리를 합니다.":"주문하실 때 이 화면을 직원에게 보여주세요."}
        </p>
      )}

      {c.status==="ISSUED"&&(
        <div className="actionbar">
          <div className="inner">
            <button className="btn secondary" disabled={notYet} onClick={()=>setOpen(true)}>직원용 사용 처리</button>
          </div>
        </div>
      )}

      <Dialog open={open} onClose={closeSheet} labelledBy="redeem-title">
        <form className="stack" onSubmit={(e:FormEvent)=>{e.preventDefault();redeem.mutate()}}>
          <h2 id="redeem-title">사용 처리할까요?</h2>
          <p className="lead">직원이 직접 6자리 PIN을 입력해주세요. 완료 후에는 되돌릴 수 없습니다.</p>
          <div className="field">
            <label htmlFor="redeem-pin">직원 PIN</label>
            <input id="redeem-pin" type="password" inputMode="numeric" pattern="[0-9]{6}" maxLength={6} autoComplete="off" autoFocus value={pin} onChange={e=>setPin(e.target.value.replace(/\D/g,""))}/>
          </div>
          {redeem.isError&&<p className="error" role="alert">{errorMessage(redeem.error)}</p>}
          <div className="sheet-actions">
            <button type="button" className="btn ghost" onClick={()=>setOpen(false)}>취소</button>
            {/* 버튼은 누르면 무슨 일이 일어나는지를 말한다. "사용 완료"는 아직 오지 않은 상태를 가리킨다. */}
            <button className="btn" disabled={redeem.isPending||pin.length!==6}>{redeem.isPending?"처리 중...":"사용 처리"}</button>
          </div>
        </form>
      </Dialog>
    </main>
  );
}
