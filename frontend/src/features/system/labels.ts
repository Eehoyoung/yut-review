import { ApiClientError } from "@/lib/api";

/**
 * 운영자 콘솔 전용 문구.
 *
 * 공용 `features/labels.ts`에 두지 않는다. 그 모듈은 손님 화면도 import하기 때문에, 여기 문자열이
 * 섞이면 "운영자", "감사 기록" 같은 말이 손님 번들에 실려 나간다.
 */
export const CONSOLE_ERROR: Record<string, string> = {
  AUTH_INVALID: "이메일 또는 비밀번호가 맞지 않습니다.",
  AUTH_RATE_LIMITED: "시도가 너무 많습니다. 5분 뒤에 다시 시도해 주세요.",
  TOTP_REQUIRED: "인증 앱의 6자리 코드를 입력해 주세요.",
  TOTP_INVALID: "코드가 맞지 않습니다. 앱에 보이는 최신 코드를 넣어 주세요.",
  TOTP_ALREADY_ENROLLED: "이미 2단계 인증이 등록되어 있습니다. 다시 로그인해 주세요.",
  ENROLLMENT_EXPIRED: "등록 시간이 지났습니다. 처음부터 다시 해 주세요.",
  NOT_FOUND: "이 주소로는 접근할 수 없습니다.",
  FORBIDDEN: "접근 권한이 없습니다.",
};

export function consoleError(error: unknown) {
  if (!(error instanceof ApiClientError)) return "잠시 후 다시 시도해 주세요.";
  return CONSOLE_ERROR[error.code] ?? error.message;
}

export const AUDIT_ACTION: Record<string, string> = {
  LOGIN: "로그인",
  LOGIN_FAILED: "로그인 실패",
  TOTP_ENROLL_START: "2단계 인증 등록 시작",
  TOTP_ENROLLED: "2단계 인증 등록 완료",
  PLAN_CHANGED: "요금제 변경",
  STORE_STATUS_CHANGED: "매장 상태 변경",
};

export const PLAN_NAME: Record<string, string> = { BASIC: "베이직", STANDARD: "스탠다드", PRO: "프로" };

export function dateTime(value: string) {
  return new Date(value).toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" });
}

export function count(value: number) {
  return value.toLocaleString("ko-KR");
}
