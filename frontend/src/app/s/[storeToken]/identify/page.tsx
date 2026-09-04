"use client";
import { FormEvent, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import { useSession } from "@/lib/session";
import { PHONE_LENGTH, isPhone, onlyDigits } from "@/features/normalize";
import type { CustomerState, GameCreated } from "@/types/api";

/**
 * 입력 검증. 첫 번째 문제와 그 칸의 id를 함께 돌려준다.
 * 문구는 무엇이 부족한지까지 말한다. "확인해 주세요"는 무엇을 고쳐야 하는지 알려주지 않는다.
 */
function problem(name: string, phone: string, agreed: boolean): { id: string; message: string } | null {
  if (!name.trim()) return { id: "name", message: "이름을 입력해 주세요." };
  if (!isPhone(phone)) return { id: "phone", message: `휴대폰 번호 ${PHONE_LENGTH}자리를 입력해 주세요.` };
  if (!agreed) return { id: "agree", message: "개인정보 수집에 동의해 주세요." };
  return null;
}

export default function Identify() {
  const token = String(useParams().storeToken);
  const router = useRouter();
  const save = useSession((s) => s.setCustomer);
  const setGame = useSession((s) => s.setGame);
  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [agreed, setAgreed] = useState(false);
  // 제출을 눌러 본 뒤에만 이유를 말한다. 이름을 치는 중에 아직 오지도 않은
  // 전화번호 칸을 지적하면 손님에게는 잔소리로 읽힌다.
  const [tried, setTried] = useState(false);
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
  const blocked = problem(name, phone, agreed);

  return (
    <main className="screen has-bar">
      <nav className="steps" aria-label="참여 단계">
        <b>1 정보 입력</b>
        <i data-on="1" />
        <span>2 윷 던지기</span>
        <i />
        <span>3 쿠폰</span>
      </nav>

      <header className="stack">
        <h1>참여자 정보를 알려주세요</h1>
        <p className="lead">쿠폰을 찾아드릴 때 쓰는 정보예요. 인증 문자는 보내지 않습니다.</p>
      </header>

      {/*
        noValidate: required는 남겨 두되(보조기술이 '필수'로 읽는다) 브라우저 기본 검증
        풍선은 끈다. 풍선이 뜨면 submit 이벤트가 오지 않아 아래 한국어 안내가 표시되지 않고,
        손님은 영어 섞인 브라우저 문구를 보게 된다.
      */}
      <form
        noValidate
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          setTried(true);
          if (!blocked) {
            mutation.mutate();
            return;
          }
          document.getElementById(blocked.id)?.focus();
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
              maxLength={PHONE_LENGTH}
              value={phone}
              onChange={(e) => setPhone(onlyDigits(e.target.value, PHONE_LENGTH))}
              required
              autoComplete="tel"
            />
            <small className="hint">010으로 시작하는 숫자 11자리</small>
          </div>
          <hr className="hair" />
          <label className="check">
            <input id="agree" type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} required />
            <span>참여 확인 및 쿠폰 제공을 위한 개인정보 수집·이용에 동의합니다.</span>
          </label>
        </div>

        {cooldown && (
          <p className="notice" role="status">
            이미 최근에 참여하셨어요. <b>{cooldown.nextPlayableDate}</b>부터 다시 참여할 수 있습니다.
          </p>
        )}
        {tried && blocked && (
          <p className="notice" role="status">
            {blocked.message}
          </p>
        )}
        {mutation.isError && (
          <p className="error" role="alert">
            {errorMessage(mutation.error)}
          </p>
        )}

        <div className="actionbar">
          <div className="inner">
            {/* 조건이 안 맞아도 비활성화하지 않는다. disabled 버튼은 탭 순서에서 빠져
                화면낭독기가 발견조차 못 하고, 무엇이 막고 있는지 알 길도 사라진다. */}
            <button className="btn" disabled={mutation.isPending}>{mutation.isPending ? "준비 중..." : "윷 던지러 가기"}</button>
          </div>
        </div>
      </form>
    </main>
  );
}
