"use client";
import { FormEvent, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import { useSession } from "@/lib/session";
import type { CustomerState, GameCreated } from "@/types/api";

export default function Identify() {
  const token = String(useParams().storeToken);
  const router = useRouter();
  const save = useSession((s) => s.setCustomer);
  const setGame = useSession((s) => s.setGame);
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [agreed, setAgreed] = useState(false);
  // Stable across retries so a double tap can never create a second game.
  const idempotencyKey = useRef(crypto.randomUUID());

  const mutation = useMutation({
    mutationFn: async () => {
      const state = await api<CustomerState>(`/public/stores/${encodeURIComponent(token)}/customer-state`, {
        method: "POST",
        body: JSON.stringify({ name, phone, privacyAgreed: agreed }),
      });
      if (state.state !== "CAN_PLAY") return { state };
      const game = await api<GameCreated>("/public/games", {
        method: "POST",
        body: JSON.stringify({ storeToken: token, name, phone, idempotencyKey: idempotencyKey.current }),
      });
      return { state, game };
    },
    onSuccess: ({ state, game }) => {
      save(token, name, phone);
      if (state.state === "HAS_ACTIVE_COUPON" && state.couponToken) {
        router.replace(`/s/${token}/coupon/${state.couponToken}`);
      } else if (game) {
        setGame(game.playId, game.animationSeed);
        router.replace(`/s/${token}/game?playId=${encodeURIComponent(game.playId)}&seed=${encodeURIComponent(game.animationSeed)}`);
      }
    },
  });

  const cooldown = mutation.data?.state.state === "COOLDOWN" ? mutation.data.state : undefined;

  return (
    <main className="screen has-bar">
      <nav className="steps" aria-label="참여 단계">
        <b>1 정보 입력</b>
        <i data-on="1" />
        <span>2 윷 던지기</span>
        <i />
        <span>3 쿠폰</span>
      </nav>

      <header className="stack" style={{ gap: "var(--s2)" }}>
        <h1>참여자 정보를 알려주세요</h1>
        <p className="lead">쿠폰을 찾아드릴 때 쓰는 정보예요. 인증 문자는 보내지 않습니다.</p>
      </header>

      <form
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          mutation.mutate();
        }}
      >
        <div className="panel stack" style={{ gap: "var(--s4)" }}>
          <div className="field">
            <label htmlFor="name">이름</label>
            <input id="name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={50} autoComplete="name" />
          </div>
          <div className="field">
            <label htmlFor="phone">휴대폰 번호</label>
            <input
              id="phone"
              type="tel"
              inputMode="numeric"
              placeholder="01012345678"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              required
              autoComplete="tel"
            />
          </div>
          <hr className="hair" />
          <label className="check">
            <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required />
            <span>참여 확인 및 쿠폰 제공을 위한 개인정보 수집·이용에 동의합니다.</span>
          </label>
        </div>

        {cooldown && (
          <p className="notice" style={{ marginTop: "var(--s4)" }} role="status">
            이미 최근에 참여하셨어요. <b>{cooldown.nextPlayableDate}</b>부터 다시 참여할 수 있습니다.
          </p>
        )}
        {mutation.isError && (
          <p className="error" style={{ marginTop: "var(--s4)" }} role="alert">
            {errorMessage(mutation.error)}
          </p>
        )}

        <div className="actionbar">
          <div className="inner">
            <button className="btn" disabled={mutation.isPending}>{mutation.isPending ? "준비 중..." : "윷 던지러 가기"}</button>
          </div>
        </div>
      </form>
    </main>
  );
}
