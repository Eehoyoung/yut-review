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
  ACCOUNT_LOCKED: "여러 번 실패해 잠긴 계정입니다. 잠시 뒤 다시 시도하거나 다른 운영자에게 잠금 해제를 요청하세요.",
  ACCOUNT_DISABLED: "사용이 중지된 계정입니다.",
  IP_NOT_ALLOWED: "허용되지 않은 접속 위치입니다.",
  TOTP_REQUIRED: "인증 앱의 6자리 코드를 입력해 주세요.",
  TOTP_INVALID: "코드가 맞지 않습니다. 앱에 보이는 최신 코드를 넣어 주세요.",
  TOTP_ALREADY_ENROLLED: "이미 2단계 인증이 등록되어 있습니다. 다시 로그인해 주세요.",
  BACKUP_CODE_INVALID: "복구 코드가 맞지 않습니다. 아직 쓰지 않은 코드인지 확인해 주세요.",
  ENROLLMENT_EXPIRED: "등록 시간이 지났습니다. 처음부터 다시 해 주세요.",
  SESSION_EXPIRED: "세션이 종료되었습니다. 다시 로그인해 주세요.",
  STEP_UP_REQUIRED: "인증 앱 코드를 한 번 더 확인해 주세요.",
  PASSWORD_CHANGE_REQUIRED: "임시 비밀번호를 먼저 변경해 주세요.",
  PASSWORD_MISMATCH: "새 비밀번호가 서로 다릅니다.",
  PASSWORD_REUSED: "지금 쓰고 있는 비밀번호와 다른 값을 넣어 주세요.",
  WEAK_PASSWORD: "영문·숫자·기호를 포함해 12자 이상이어야 합니다.",
  LAST_OWNER: "마지막 OWNER는 권한을 내리거나 중지할 수 없습니다.",
  INVALID_IP_RULE: "IP 형식을 확인해 주세요. 예: 203.0.113.4, 10.0.0.0/8",
  DUPLICATE_EMAIL: "이미 사용 중인 이메일입니다.",
  OPERATOR_NOT_FOUND: "운영자 계정을 찾을 수 없습니다.",
  NOT_FOUND: "이 주소로는 접근할 수 없습니다.",
  FORBIDDEN: "이 작업을 할 수 있는 권한이 없습니다.",
};

export function consoleError(error: unknown) {
  if (!(error instanceof ApiClientError)) return "잠시 후 다시 시도해 주세요.";
  return CONSOLE_ERROR[error.code] ?? error.message;
}

export const AUDIT_ACTION: Record<string, string> = {
  LOGIN: "로그인",
  LOGIN_FAILED: "로그인 실패",
  LOGIN_BLOCKED: "로그인 차단",
  TOTP_ENROLL_START: "2단계 인증 등록 시작",
  TOTP_ENROLLED: "2단계 인증 등록 완료",
  TOTP_RESET: "2단계 인증 초기화",
  BACKUP_CODE_USED: "복구 코드 사용",
  BACKUP_CODES_ISSUED: "복구 코드 발급",
  STEP_UP: "재인증",
  STEP_UP_FAILED: "재인증 실패",
  LOGOUT: "로그아웃",
  SESSION_REVOKED: "세션 종료",
  PASSWORD_CHANGED: "비밀번호 변경",
  OPERATOR_CREATED: "운영자 추가",
  OPERATOR_UPDATED: "운영자 변경",
  PLAN_CHANGED: "요금제 변경",
  STORE_STATUS_CHANGED: "매장 상태 변경",
  ACCESS_DENIED: "권한 없는 시도",
  AUDIT_EXPORTED: "기록 내보내기",
};

/** 감사 기록 필터의 선택지. 값은 서버가 쓰는 행위 이름 그대로다. */
export const AUDIT_FILTERS = [
  "LOGIN",
  "LOGIN_FAILED",
  "LOGIN_BLOCKED",
  "STEP_UP_FAILED",
  "SESSION_REVOKED",
  "PLAN_CHANGED",
  "STORE_STATUS_CHANGED",
  "OPERATOR_CREATED",
  "OPERATOR_UPDATED",
  "TOTP_RESET",
  "PASSWORD_CHANGED",
  "ACCESS_DENIED",
  "AUDIT_EXPORTED",
];

export const ROLE_LABEL: Record<string, string> = {
  VIEWER: "열람",
  OPERATOR: "운영",
  OWNER: "총괄",
};

export const ROLE_DESCRIPTION: Record<string, string> = {
  VIEWER: "현황과 기록을 보기만 합니다.",
  OPERATOR: "매장 요금제와 운영 상태를 바꿀 수 있습니다.",
  OWNER: "운영자 계정 관리와 기록 검증까지 할 수 있습니다.",
};

export const SESSION_END_REASON: Record<string, string> = {
  LOGOUT: "로그아웃",
  EXPIRED: "시간 만료",
  IDLE_TIMEOUT: "자리 비움",
  DEVICE_MISMATCH: "다른 기기에서 사용",
  SESSION_LIMIT: "세션 수 초과",
  PASSWORD_CHANGED: "비밀번호 변경",
  ACCOUNT_UPDATED: "권한 변경",
  TOTP_RESET: "2단계 인증 초기화",
  USER_REVOKED: "본인이 종료",
  ADMIN_REVOKED: "운영자가 종료",
};

export const PLAN_NAME: Record<string, string> = { BASIC: "베이직", STANDARD: "스탠다드", PRO: "프로" };

export function dateTime(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" }) : "-";
}

export function count(value: number) {
  return value.toLocaleString("ko-KR");
}

/** 권한이 모자란 메뉴는 아예 보여 주지 않는다. */
export function atLeast(role: string | undefined, minimum: "VIEWER" | "OPERATOR" | "OWNER") {
  const order = ["VIEWER", "OPERATOR", "OWNER"];
  return order.indexOf(role ?? "") >= order.indexOf(minimum);
}
