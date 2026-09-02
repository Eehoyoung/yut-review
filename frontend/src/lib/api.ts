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

export function setAdminToken(token: string) {
  sessionStorage.setItem("adminToken", token);
}

const friendly: Record<string, string> = {
  STORE_NOT_FOUND: "매장을 찾을 수 없습니다.", STORE_INACTIVE: "현재 이벤트를 운영하지 않는 매장입니다.",
  QR_TOKEN_INVALID: "유효하지 않은 QR입니다.", QR_TOKEN_REVOKED: "사용이 중지된 QR입니다.",
  INVALID_PHONE: "휴대폰 번호를 확인해주세요.", PRIVACY_CONSENT_REQUIRED: "개인정보 수집에 동의해주세요.",
  STAFF_PIN_INVALID: "직원 PIN이 올바르지 않습니다.", STAFF_PIN_RATE_LIMITED: "입력 횟수를 초과했습니다. 잠시 후 다시 시도해주세요.",
  COUPON_NOT_YET_VALID: "아직 사용할 수 없는 쿠폰입니다.", COUPON_EXPIRED: "사용 기간이 지난 쿠폰입니다.",
  COUPON_ALREADY_REDEEMED: "이미 사용한 쿠폰입니다.", COUPON_NOT_ACTIVE: "사용할 수 없는 쿠폰입니다.",
  AUTH_INVALID: "아이디 또는 비밀번호가 올바르지 않습니다.", AUTH_RATE_LIMITED: "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요.",
  INVALID_LOGIN_ID: "아이디는 영문 소문자, 숫자, _ 조합 4~20자로 입력해주세요.", PASSWORD_MISMATCH: "비밀번호가 일치하지 않습니다.",
  WEAK_PASSWORD: "비밀번호는 영문과 숫자를 포함해 10자 이상이어야 합니다.", INVALID_EMAIL: "이메일 주소를 확인해주세요.",
  INVALID_BUSINESS_NUMBER: "사업자등록번호 10자리를 확인해주세요.", DUPLICATE_LOGIN_ID: "이미 사용 중인 아이디입니다.",
  DUPLICATE_EMAIL: "이미 가입된 이메일입니다.", DUPLICATE_BUSINESS_NUMBER: "이미 등록된 사업자등록번호입니다.",
};

export function errorMessage(error: unknown) {
  return error instanceof ApiClientError ? friendly[error.code] ?? error.message : "잠시 후 다시 시도해주세요.";
}
