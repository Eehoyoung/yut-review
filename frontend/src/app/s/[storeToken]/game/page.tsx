"use client";
import { useEffect, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import YutGame from "@/components/yut/YutGame";
import { api } from "@/lib/api";
import type { RevealResponse } from "@/types/api";

const playedKey = (playId: string) => `yut:played:${playId}`;

function markPlayed(playId: string) {
  try {
    sessionStorage.setItem(playedKey(playId), "1");
  } catch {
    // Private mode without storage still works; the server result is unchanged either way.
  }
}

function wasPlayed(playId: string) {
  try {
    return sessionStorage.getItem(playedKey(playId)) === "1";
  } catch {
    return false;
  }
}

export default function Game() {
  const token = String(useParams().storeToken);
  const query = useSearchParams();
  const router = useRouter();
  const qc = useQueryClient();
  const playId = query.get("playId") ?? "";
  const seed = query.get("seed") ?? "";
  const [replaying, setReplaying] = useState(false);

  // Coming back to this page (back button, bfcache) must not look like a new turn: the game is
  // already played, so send the customer to the result instead of offering another throw.
  useEffect(() => {
    if (!playId) return;
    const done = () => {
      if (!wasPlayed(playId)) return;
      setReplaying(true);
      router.replace(`/s/${token}/result/${playId}`);
    };
    done();
    window.addEventListener("pageshow", done);
    return () => window.removeEventListener("pageshow", done);
  }, [playId, router, token]);

  if (!playId || !seed) {
    return (
      <main className="screen">
        <h1>게임을 시작할 수 없어요</h1>
        <p className="error" role="alert">QR을 다시 스캔해 주세요.</p>
      </main>
    );
  }

  if (replaying) {
    return (
      <main className="screen" aria-live="polite" aria-busy="true">
        <span className="visually-hidden">결과를 불러오는 중</span>
        <div className="skeleton" style={{ height: 80, width: 160 }} />
      </main>
    );
  }

  return (
    <main>
      <YutGame
        playId={playId}
        animationSeed={seed}
        reveal={() => api<RevealResponse>(`/public/games/${encodeURIComponent(playId)}/reveal`, { method: "POST" })}
        onRevealed={(revealed) => {
          markPlayed(playId);
          // 결과는 방금 손에 들어와 있다. 캐시에 얹어 두면 결과 화면이 같은 값을 다시
          // 받아오려고 뼈대를 띄우는 일이 없다. 이 흐름에서 가장 기다리기 싫은 한 순간이다.
          qc.setQueryData(["result", playId], revealed);
          router.replace(`/s/${token}/result/${playId}`);
        }}
      />
    </main>
  );
}
