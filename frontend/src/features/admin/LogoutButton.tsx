"use client";
import { useQueryClient } from "@tanstack/react-query";
import { clearAdminSession } from "@/lib/api";

/**
 * 로그아웃.
 *
 * 토큰을 지우는 것만으로는 부족하다. TanStack Query 캐시에 이전 계정의 매장 목록·집계가 남아
 * 있어서, 같은 브라우저에서 다른 계정으로 로그인하면 잠깐 남의 데이터가 보인다. 그래서 캐시도
 * 함께 비운다.
 *
 * 로그아웃 뒤에는 그 영역의 로그인 화면으로 간다. 매장 관리자는 `/admin/login`, 운영자는 `/operator/login`.
 * 운영자를 매장 관리자 로그인으로 보내면 비밀번호 로그인이 막혀 있어 거기서 오류만 본다.
 *
 * `window.location.assign`으로 보낸다. `router.push`는 클라이언트 전환이라 메모리에 남은 상태가
 * 따라온다. 로그아웃은 그 상태를 버리는 것이 목적이다.
 */
export function LogoutButton({
  className = "btn ghost btn-inline",
  loginPath = "/admin/login",
}: {
  className?: string;
  loginPath?: "/admin/login" | "/operator/login";
}) {
  const qc = useQueryClient();
  return (
    <button
      type="button"
      className={className}
      onClick={() => {
        clearAdminSession();
        qc.clear();
        window.location.assign(loginPath);
      }}
    >
      로그아웃
    </button>
  );
}
