"use client";
import { useEffect, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
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
      <main className="shell">
        <p className="error">게임 정보가 없습니다. 참여 절차를 다시 시작해주세요.</p>
      </main>
    );
  }

  if (replaying) {
    return <main className="shell">결과를 불러오는 중...</main>;
  }

  return (
    <main style={{ minHeight: "100dvh" }}>
      <YutGame
        playId={playId}
        animationSeed={seed}
        reveal={() => api<RevealResponse>(`/public/games/${encodeURIComponent(playId)}/reveal`, { method: "POST" })}
        onRevealed={() => {
          markPlayed(playId);
          router.replace(`/s/${token}/result/${playId}`);
        }}
      />
    </main>
  );
}
