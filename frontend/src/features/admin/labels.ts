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
