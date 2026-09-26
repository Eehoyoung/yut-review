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
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });
  const body = (await response.json().catch(() => null)) as Envelope<T> | null;
  if (response.status === 401 && path.startsWith("/admin/operator") && !path.startsWith("/admin/operator-auth/") && typeof window !== "undefined") {
    clearAdminSession();
    window.location.assign("/admin/operator/login");
  } else if (response.status === 401 && path.startsWith("/admin/") && path !== "/admin/auth/login"
    && !path.startsWith("/admin/operator-auth/") && typeof window !== "undefined") {
    clearAdminSession();
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

export function setOperatorSession(token: string, expiresAt: string) {
  sessionStorage.setItem("adminToken", token);
  sessionStorage.setItem("operatorExpiresAt", expiresAt);
}

/**
 * 로그아웃 시 지우는 것 전부.
 *
 * 운영자 OTP 세션의 만료 시각도 함께 지운다.
 */
export function clearAdminSession() {
  try {
    sessionStorage.removeItem("adminToken");
    sessionStorage.removeItem("operatorExpiresAt");
  } catch {
    // 사생활 보호 모드에서 sessionStorage 접근이 막힐 수 있다. 그래도 이동은 해야 한다.
  }
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
  QR_TOKEN_INVALID: "QR 코드를 확인해 주세요.", QR_TOKEN_REVOKED: "사용이 중지된 QR입니다.",
  INVALID_PHONE: "휴대폰 번호를 확인해 주세요.", PRIVACY_CONSENT_REQUIRED: "개인정보 수집에 동의해 주세요.",
  TERMS_CONSENT_REQUIRED: "서비스 이용약관에 동의해 주세요.",
  STAFF_PIN_INVALID: "직원 PIN이 맞지 않아요. 다시 확인해 주세요.", STAFF_PIN_RATE_LIMITED: "잠시 후 PIN을 다시 입력해 주세요.",
  COUPON_NOT_YET_VALID: "아직 사용할 수 없는 쿠폰입니다.", COUPON_EXPIRED: "사용 기간이 지난 쿠폰입니다.",
  COUPON_ALREADY_REDEEMED: "이미 사용한 쿠폰입니다.", COUPON_NOT_ACTIVE: "사용할 수 없는 쿠폰입니다.",
  AUTH_INVALID: "이메일 또는 비밀번호가 맞지 않아요.", AUTH_RATE_LIMITED: "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.",
  OPERATOR_OTP_REQUIRED: "시스템 운영자는 이메일 인증번호로 로그인해 주세요.",
  OPERATOR_OTP_INVALID: "인증번호가 올바르지 않거나 2분이 지났습니다.",
  OPERATOR_OTP_RATE_LIMITED: "인증 요청이 너무 많습니다. 15분 후 다시 시도해 주세요.",
  OPERATOR_OTP_EMAIL_UNAVAILABLE: "운영자 인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.",
  OPERATOR_SESSION_REQUIRED: "운영자 세션이 만료되었습니다. 이메일 인증을 다시 진행해 주세요.",
  PASSWORD_MISMATCH: "비밀번호가 일치하지 않습니다.",
  WEAK_PASSWORD: "비밀번호는 영문과 숫자를 포함해 10자 이상이어야 합니다.", INVALID_EMAIL: "이메일 주소를 확인해 주세요.",
  INVALID_BUSINESS_NUMBER: "사업자등록번호는 숫자 10자리로 입력해 주세요.",
  DUPLICATE_EMAIL: "이미 가입된 이메일입니다.", DUPLICATE_BUSINESS_NUMBER: "이미 등록된 사업자등록번호입니다.",
  INVITE_CODE_NOT_FOUND: "존재하지 않는 초대코드예요.",
  INVITE_CODE_SELF: "자신의 초대코드는 사용할 수 없어요.",
  INVALID_OPENING_DATE: "개업일자를 '-' 없이 8자리 숫자로 입력해 주세요. (예: 20200101)",
  BUSINESS_NOT_VERIFIED: "국세청에 등록된 사업자 정보와 일치하지 않습니다. 사업자등록증의 번호·개업일자·대표자명을 확인해 주세요.",
  BUSINESS_CLOSED: "폐업 처리된 사업자등록번호입니다.",
  BUSINESS_SUSPENDED: "휴업 중인 사업자등록번호입니다.",
  BUSINESS_VERIFICATION_UNAVAILABLE: "사업자 정보를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
  ACCOUNT_RECOVERY_NOT_FOUND: "입력한 정보와 일치하는 계정을 찾을 수 없습니다.",
  ACCOUNT_RECOVERY_RATE_LIMITED: "계정 찾기 요청이 너무 많습니다. 한 시간 후 다시 시도해 주세요.",
  RECOVERY_CODE_INVALID: "인증번호가 올바르지 않거나 만료되었습니다.",
  RECOVERY_EMAIL_UNAVAILABLE: "인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.",
  STORE_LIMIT_REACHED: "한 계정이 관리할 수 있는 매장 수를 넘었습니다.",
  RATE_LIMITED: "요청이 너무 많아요. 잠시 후 다시 시도해 주세요.",
  GAME_RATE_LIMITED: "지금 참여가 몰리고 있어요. 잠시 후 다시 시도해 주세요.",
  STORE_DAILY_LIMIT: "오늘은 참여가 마감됐어요. 내일 다시 참여해 주세요.",
  RECOVERY_TICKET_INVALID: "쿠폰 확인 시간이 지났어요. 번호를 다시 입력해 주세요.",
  STORE_PENDING_APPROVAL: "아직 준비 중인 매장이에요. 직원에게 문의해 주세요.",
  STORE_REJECTED: "현재 이벤트를 운영하지 않는 매장입니다.",
};

export function errorMessage(error: unknown) {
  return error instanceof ApiClientError ? friendly[error.code] ?? error.message : "잠시 후 다시 시도해 주세요.";
}
