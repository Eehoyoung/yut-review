import type { PrintKitStatus } from "@/types/api";

/**
 * 운영자 콘솔(/operator) 전용 문구. 매장 관리자 화면(/admin)과 공유하는 매장 상태·요금제 라벨은
 * `features/admin/labels.ts`에 있다. 여기 것은 매장 관리자 번들에 들어가지 않는다.
 */

/**
 * 차단 코드를 운영자가 읽을 문장으로. 서버 코드를 그대로 보여 주면 무엇이 막힌 것인지
 * 매번 스펙 문서를 찾아봐야 한다.
 */
export const THROTTLE_LABEL: Record<string, string> = {
  GAME_RATE_LIMITED: "게임 생성 (매장·IP 분당 한도)",
  STORE_DAILY_LIMIT: "게임 생성 (매장 일일 상한)",
  SIGNUP_RATE_LIMITED: "매장 가입 신청",
  AUTH_RATE_LIMITED: "매장 관리자 로그인",
  STAFF_PIN_RATE_LIMITED: "직원 PIN 확인",
  RATE_LIMITED: "고객 상태 조회",
};

/** 입점 키트(A6 안내물 3종 + 테이블 스티커 10장) 실물 발송 단계. 운영자 콘솔 전용. */
export const PRINT_KIT_STATUS_LABEL: Record<PrintKitStatus, string> = {
  WAITING: "인쇄 대기",
  PRINTING: "인쇄 중",
  PRINTED: "인쇄 완료",
  SHIPPED: "발송 완료",
};

export const PRINT_KIT_STATUS_TONE: Record<PrintKitStatus, string> = {
  WAITING: "wait",
  PRINTING: "wood",
  PRINTED: "ok",
  SHIPPED: "off",
};
