import type { Envelope } from "@/types/api";

export class ApiClientError extends Error {
  constructor(public readonly code: string, message: string) {
    super(message);
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const token = typeof window === "undefined" ? null : sessionStorage.getItem("adminToken");
  const response = await fetch(`/api${path}`, {
    ...init,
    credentials: "include",
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}), ...init?.headers },
  });
  const body = (await response.json().catch(() => null)) as Envelope<T> | null;
  if (response.status === 401 && path.startsWith("/admin/") && path !== "/admin/auth/login" && typeof window !== "undefined") {
    sessionStorage.removeItem("adminToken");
    window.location.assign("/admin/login");
  }
  if (!response.ok || !body?.success || body.data === null) {
    throw new ApiClientError(body?.error?.code ?? "NETWORK_ERROR", body?.error?.message ?? "요청을 처리하지 못했습니다.");
  }
  return body.data;
}

/**
 * 인증이 필요한 파일 내려받기.
 *
 * `<a href>`로는 sessionStorage의 JWT가 실리지 않아 401이 난다. 그래서 직접 받아서 blob으로
 * 넘긴다. 파일명은 서버가 Content-Disposition에 담아 보낸 값을 그대로 쓴다.
 */
export async function downloadWithAuth(path: string, fallbackName: string) {
  const token = sessionStorage.getItem("adminToken");
  const response = await fetch(`/api${path}`, {
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
  // 브라우저가 저장을 시작한 뒤에 풀어 준다. 바로 풀면 큰 파일에서 저장이 끊긴다.
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

export function setAdminToken(token: string) {
  sessionStorage.setItem("adminToken", token);
}

/**
 * 고객이 실제로 만날 수 있는 오류만 여기 둔다.
 *
 * 요금제·AI 오류 문구를 여기 넣었다가 고객 번들 다섯 개에 '요금제'가 실려 나갔다. 이 모듈은 손님
 * 화면도 import한다. 관리자 전용 코드는 서버가 보내는 메시지를 그대로 쓰면 되고, 실제로 그쪽이 더
 * 구체적이다(어느 날짜부터 볼 수 있는지까지 들어 있다).
 */
const friendly: Record<string, string> = {
  STORE_NOT_FOUND: "매장을 찾을 수 없습니다.", STORE_INACTIVE: "현재 이벤트를 운영하지 않는 매장입니다.",
  QR_TOKEN_INVALID: "유효하지 않은 QR입니다.", QR_TOKEN_REVOKED: "사용이 중지된 QR입니다.",
  INVALID_PHONE: "휴대폰 번호를 확인해주세요.", PRIVACY_CONSENT_REQUIRED: "개인정보 수집에 동의해주세요.",
  STAFF_PIN_INVALID: "직원 PIN이 올바르지 않습니다.", STAFF_PIN_RATE_LIMITED: "입력 횟수를 초과했습니다. 잠시 후 다시 시도해주세요.",
  COUPON_NOT_YET_VALID: "아직 사용할 수 없는 쿠폰입니다.", COUPON_EXPIRED: "사용 기간이 지난 쿠폰입니다.",
  COUPON_ALREADY_REDEEMED: "이미 사용한 쿠폰입니다.", COUPON_NOT_ACTIVE: "사용할 수 없는 쿠폰입니다.",
  AUTH_INVALID: "이메일 또는 비밀번호가 올바르지 않습니다.", AUTH_RATE_LIMITED: "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요.",
  PASSWORD_MISMATCH: "비밀번호가 일치하지 않습니다.",
  WEAK_PASSWORD: "비밀번호는 영문과 숫자를 포함해 10자 이상이어야 합니다.", INVALID_EMAIL: "이메일 주소를 확인해주세요.",
  INVALID_BUSINESS_NUMBER: "사업자등록번호는 숫자 10자리로 입력해주세요.",
  DUPLICATE_EMAIL: "이미 가입된 이메일입니다.", DUPLICATE_BUSINESS_NUMBER: "이미 등록된 사업자등록번호입니다.",
  STORE_LIMIT_REACHED: "한 계정이 관리할 수 있는 매장 수를 넘었습니다.",
};

export function errorMessage(error: unknown) {
  return error instanceof ApiClientError ? friendly[error.code] ?? error.message : "잠시 후 다시 시도해주세요.";
}
