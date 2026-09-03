import type { AiFeature, Plan } from "@/types/api";

/**
 * 관리자 화면에서만 쓰는 라벨.
 *
 * 공용 labels.ts에 두면 고객 화면이 그 모듈을 import하는 순간 요금제와 AI 문자열까지 고객 번들에
 * 실린다. 실제로 그렇게 됐던 적이 있어 여기로 옮겼다. 손님에게 갈 코드에는 요금제 개념이 없어야 한다.
 */
export const PLAN_LABEL: Record<Plan, string> = { BASIC: "베이직", STANDARD: "스탠다드", PRO: "프로" };

export const PLAN_TAGLINE: Record<Plan, string> = {
  BASIC: "윷리뷰 핵심 기능",
  STANDARD: "가장 인기",
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
  PERSONAL_DATA_NOT_ALLOWED: "전화번호나 이메일은 AI에 보낼 수 없습니다. 그 부분만 빼고 다시 써 주세요.",
};
