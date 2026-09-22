import type { AiFeature, Plan, StoreStatus } from "@/types/api";

/**
 * 관리자 화면에서만 쓰는 라벨.
 *
 * 공용 labels.ts에 두면 고객 화면이 그 모듈을 import하는 순간 요금제와 AI 문자열까지 고객 번들에
 * 실린다. 실제로 그렇게 됐던 적이 있어 여기로 옮겼다. 손님에게 갈 코드에는 요금제 개념이 없어야 한다.
 */
export const PLAN_LABEL: Record<Plan, string> = { BASIC: "베이직", STANDARD: "스탠다드", PRO: "프로" };

export const PLAN_TAGLINE: Record<Plan, string> = {
  BASIC: "소담한판 핵심 기능",
  STANDARD: "분석·AI 기능",
  PRO: "AI 매장 운영",
};

export const AI_FEATURE_LABEL: Record<AiFeature, string> = {
  AI_EVENT_COPY: "이벤트 문구",
  AI_REPORT: "운영 리포트",
  AI_IMPROVEMENT: "개선 제안",
  AI_CHAT: "AI 매니저",
};

export const priceLabel = (krw: number) => `월 ${krw.toLocaleString("ko-KR")}원`;

/**
 * 관리자 화면에서만 나오는 오류 문구.
 *
 * 공용 lib/api.ts에 두면 고객 번들로 샌다(실제로 '요금제'가 새어 나간 적이 있다). 대부분은 서버
 * 메시지를 그대로 쓰는 편이 더 구체적이라, 여기에는 화면에서 다시 풀어 줘야 하는 것만 둔다.
 */
export const ADMIN_ERROR_HINT: Record<string, string> = {
  PERSONAL_DATA_NOT_ALLOWED: "전화번호와 이메일을 빼고 다시 입력해 주세요.",
};

/** 매장 승인 상태. 손님 화면에는 승인이라는 개념 자체가 없으므로 여기에 둔다. */
export const STORE_STATUS_LABEL: Record<StoreStatus, string> = {
  PENDING_APPROVAL: "승인 대기",
  ACTIVE: "운영 중",
  INACTIVE: "운영 중지",
  REJECTED: "승인 거부",
};

export const STORE_STATUS_TONE: Record<StoreStatus, string> = {
  PENDING_APPROVAL: "wait",
  ACTIVE: "ok",
  INACTIVE: "off",
  REJECTED: "bad",
};

/** 대시보드가 막혔을 때 무엇을 기다리는지 한 문장으로. */
export const STORE_STATUS_HINT: Record<StoreStatus, string> = {
  PENDING_APPROVAL: "소담랩스 운영자가 사업자 정보를 확인하고 있습니다. 승인되면 QR 안내물, 포스터, 직원 PIN이 열립니다.",
  ACTIVE: "",
  INACTIVE: "운영을 중지한 매장입니다. 다시 열려면 소담랩스에 문의해 주세요.",
  REJECTED: "승인이 거부된 매장입니다. 아래 사유를 확인하고 소담랩스에 문의해 주세요.",
};

/**
 * 차단 코드를 운영자가 읽을 문장으로. 서버 코드를 그대로 보여 주면 무엇이 막힌 것인지
 * 매번 스펙 문서를 찾아봐야 한다.
 */
export const THROTTLE_LABEL: Record<string, string> = {
  GAME_RATE_LIMITED: "게임 생성 (매장·IP 분당 한도)",
  STORE_DAILY_LIMIT: "게임 생성 (매장 일일 상한)",
  SIGNUP_RATE_LIMITED: "매장 가입 신청",
  AUTH_RATE_LIMITED: "관리자 로그인",
  STAFF_PIN_RATE_LIMITED: "직원 PIN 확인",
  RATE_LIMITED: "고객 상태 조회",
};
