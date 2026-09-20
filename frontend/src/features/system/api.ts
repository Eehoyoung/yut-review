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
  // 세션 자체가 무효일 때만 토큰을 버린다. STEP_UP_REQUIRED와 PASSWORD_CHANGE_REQUIRED 같은
  // 복구 가능한 403은 현재 화면에서 대화상자/비밀번호 변경으로 이어져야 한다.
  const terminalSessionCodes = new Set([
    "SESSION_EXPIRED",
    "AUTH_REQUIRED",
    "INVALID_TOKEN",
    "ACCOUNT_DISABLED",
    "FORBIDDEN",
    "NOT_FOUND",
  ]);
  if (terminalSessionCodes.has(body?.error?.code ?? "")
      && !path.startsWith("/auth/")) {
    clearOperatorToken();
    if (typeof window !== "undefined" && window.location.pathname !== LOGIN_PATH) window.location.assign(LOGIN_PATH);
  }
  if (!response.ok || !body?.success || body.data === null) {
    throw new ApiClientError(body?.error?.code ?? "NETWORK_ERROR", body?.error?.message ?? "요청을 처리하지 못했습니다.");
  }
  return body.data;
}

/**
 * 인증이 필요한 파일 내려받기(감사 기록 CSV).
 *
 * `<a href>`로는 토큰이 실리지 않아 401이 난다. 매장 콘솔의 같은 기능과 분리해 두는 이유는
 * 토큰 보관함이 다르기 때문이다.
 */
export async function downloadWithOperatorToken(path: string, fallbackName: string) {
  const token = operatorToken();
  const response = await fetch(`/api/system${path}`, {
    credentials: "include",
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as Envelope<unknown> | null;
    throw new ApiClientError(body?.error?.code ?? "NETWORK_ERROR", body?.error?.message ?? "내려받지 못했습니다.");
  }
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const named = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = named ? decodeURIComponent(named[1]) : fallbackName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
