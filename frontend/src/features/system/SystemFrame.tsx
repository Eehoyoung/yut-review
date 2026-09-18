"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { leaveConsole, operatorToken, opsApi } from "@/features/system/api";
import type { OperatorMe } from "@/types/api";

const menus = [
  ["/admin/system", "개요"],
  ["/admin/system/stores", "매장"],
  ["/admin/system/audit", "접근 기록"],
];

/** 손을 떼고 자리를 비운 화면을 열어 두지 않는다. 마지막 조작에서 이만큼 지나면 토큰을 버린다. */
const IDLE_MINUTES = 15;

export function SystemFrame({ title, children }: { title: string; children: React.ReactNode }) {
  const pathname = usePathname();
  const me = useQuery({
    queryKey: ["ops-me"],
    queryFn: () => opsApi<OperatorMe>("/me"),
    retry: false,
    staleTime: 60_000,
  });

  // 토큰이 없으면 화면을 그리기 전에 로그인으로 보낸다. 서버가 어차피 거절하지만, 빈 콘솔의
  // 구조(메뉴 이름과 지표 자리)를 보여 줄 이유가 없다.
  useEffect(() => {
    if (!operatorToken()) leaveConsole();
  }, []);

  useEffect(() => {
    let timer = 0;
    const reset = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(leaveConsole, IDLE_MINUTES * 60_000);
    };
    const events = ["pointerdown", "keydown", "visibilitychange"] as const;
    events.forEach((event) => window.addEventListener(event, reset));
    reset();
    return () => {
      window.clearTimeout(timer);
      events.forEach((event) => window.removeEventListener(event, reset));
    };
  }, []);

  return (
    <main className="admin-shell">
      <header className="admin-head">
        <h1>{title}</h1>
        <button className="btn secondary btn-inline" onClick={leaveConsole}>
          로그아웃
        </button>
      </header>
      <nav className="nav" aria-label="운영자 메뉴">
        {menus.map(([href, label]) => (
          <Link key={href} href={href} aria-current={pathname === href ? "page" : undefined}>
            {label}
          </Link>
        ))}
      </nav>
      {/* 지금 어떤 계정으로, 얼마짜리 세션으로 들어와 있는지 항상 보이게 둔다. */}
      <p className="hint wrap-anywhere">
        {me.data ? `${me.data.email} · ${me.data.sessionMinutes}분 세션` : "세션 확인 중"}
        {me.data?.ipRestricted ? " · IP 제한 적용" : ""}
      </p>
      {children}
    </main>
  );
}
