"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { clearOperatorToken, leaveConsole, operatorToken, opsApi } from "@/features/system/api";
import { PasswordForm } from "@/features/system/PasswordForm";
import { StepUpDialog } from "@/features/system/StepUpDialog";
import { ROLE_LABEL, atLeast } from "@/features/system/labels";
import type { OperatorMe } from "@/types/api";

const menus: [string, string, "VIEWER" | "OPERATOR" | "OWNER"][] = [
  ["/admin/system", "개요", "VIEWER"],
  ["/admin/system/stores", "매장", "VIEWER"],
  ["/admin/system/operators", "운영자", "OWNER"],
  ["/admin/system/audit", "접근 기록", "VIEWER"],
  ["/admin/system/security", "보안", "VIEWER"],
];

/**
 * 콘솔 공통 틀.
 *
 * 화면이 하는 일은 셋이다. 권한에 맞는 메뉴만 보여 주고, 임시 비밀번호를 쓰는 계정은 변경 화면에
 * 묶어 두고, 자리를 비운 화면을 닫는다. 셋 다 서버가 다시 판단하므로 여기 것은 안내일 뿐이다.
 */
export function SystemFrame({ title, children }: { title: string; children: React.ReactNode }) {
  const pathname = usePathname();
  const qc = useQueryClient();
  const me = useQuery({
    queryKey: ["ops-me"],
    queryFn: () => opsApi<OperatorMe>("/me"),
    retry: false,
    staleTime: 30_000,
  });

  // 토큰이 없으면 화면을 그리기 전에 로그인으로 보낸다. 서버가 어차피 거절하지만, 빈 콘솔의
  // 구조(메뉴 이름과 지표 자리)를 보여 줄 이유가 없다.
  useEffect(() => {
    if (!operatorToken()) leaveConsole();
  }, []);

  // 서버도 유휴 세션을 닫지만, 화면을 먼저 닫아 어깨너머로 보이는 시간을 줄인다.
  const idleMinutes = me.data?.idleMinutes ?? 15;
  useEffect(() => {
    let timer = 0;
    const reset = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(() => {
        clearOperatorToken();
        leaveConsole();
      }, idleMinutes * 60_000);
    };
    const events = ["pointerdown", "keydown", "visibilitychange"] as const;
    events.forEach((event) => window.addEventListener(event, reset));
    reset();
    return () => {
      window.clearTimeout(timer);
      events.forEach((event) => window.removeEventListener(event, reset));
    };
  }, [idleMinutes]);

  const role = me.data?.consoleRole;

  return (
    <main className="admin-shell">
      <header className="admin-head">
        <h1>{title}</h1>
        <button className="btn secondary btn-inline" onClick={() => void logout()}>
          로그아웃
        </button>
      </header>

      {me.data?.mustChangePassword ? (
        <>
          <p className="notice" role="status">
            임시 비밀번호로 접속했습니다. 비밀번호를 바꾸기 전에는 다른 화면을 열 수 없습니다.
          </p>
          <PasswordForm onDone={() => qc.invalidateQueries({ queryKey: ["ops-me"] })} />
        </>
      ) : (
        <>
          <nav className="nav" aria-label="운영자 메뉴">
            {menus
              .filter(([, , minimum]) => atLeast(role, minimum))
              .map(([href, label]) => (
                <Link key={href} href={href} aria-current={pathname === href ? "page" : undefined}>
                  {label}
                </Link>
              ))}
          </nav>
          {/* 지금 어떤 계정으로, 어떤 권한으로 들어와 있는지 항상 보이게 둔다. */}
          <p className="hint wrap-anywhere">
            {me.data ? `${me.data.email} · ${ROLE_LABEL[me.data.consoleRole] ?? me.data.consoleRole} 권한` : "세션 확인 중"}
            {me.data ? ` · ${me.data.idleMinutes}분 자리 비움 시 종료` : ""}
            {me.data?.ipRestricted ? " · IP 제한" : ""}
          </p>
          {children}
        </>
      )}
      <StepUpDialog />
    </main>
  );
}

/** 서버 세션까지 닫고 나간다. 토큰만 버리면 그 토큰은 남은 시간 동안 살아 있다. */
async function logout() {
  try {
    await opsApi("/session/logout", { method: "POST" });
  } catch {
    // 이미 끝난 세션이면 그대로 나가면 된다.
  }
  clearOperatorToken();
  leaveConsole();
}
