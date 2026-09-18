"use client";
import { ApiClientError } from "@/lib/api";
import type { Envelope } from "@/types/api";

/**
 * 운영자 콘솔 전용 클라이언트.
 *
 * 매장 콘솔과 토큰 보관함부터 나눈다(`opsToken` vs `adminToken`). 같은 칸을 쓰면 한쪽에서
 * 로그아웃할 때 다른 쪽 토큰이 지워지거나, 더 나쁘게는 매장 콘솔 토큰이 운영자 요청에 실린다.
 * sessionStorage라 탭을 닫으면 사라진다. 운영자 토큰이 브라우저에 남아 있을 이유가 없다.
 */
const TOKEN_KEY = "opsToken";
const LOGIN_PATH = "/admin/system/login";

export function operatorToken() {
  return typeof window === "undefined" ? null : sessionStorage.getItem(TOKEN_KEY);
}

export function setOperatorToken(token: string) {
  sessionStorage.setItem(TOKEN_KEY, token);
}

export function clearOperatorToken() {
  sessionStorage.removeItem(TOKEN_KEY);
}

export function leaveConsole() {
  clearOperatorToken();
  window.location.assign(LOGIN_PATH);
}

export async function opsApi<T>(path: string, init?: RequestInit): Promise<T> {
  const token = operatorToken();
  const response = await fetch(`/api/system${path}`, {
    ...init,
    credentials: "include",
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}), ...init?.headers },
  });
  const body = (await response.json().catch(() => null)) as Envelope<T> | null;
  // 만료·권한 회수·IP 차단은 모두 화면에서 같은 결말이다. 토큰을 버리고 로그인으로 돌아간다.
  if ((response.status === 401 || response.status === 403 || response.status === 404) && !path.startsWith("/auth/")) {
    clearOperatorToken();
    if (typeof window !== "undefined" && window.location.pathname !== LOGIN_PATH) window.location.assign(LOGIN_PATH);
  }
  if (!response.ok || !body?.success || body.data === null) {
    throw new ApiClientError(body?.error?.code ?? "NETWORK_ERROR", body?.error?.message ?? "요청을 처리하지 못했습니다.");
  }
  return body.data;
}
