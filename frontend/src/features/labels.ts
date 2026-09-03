import type { CouponStatus, RedeemPolicy, YutResult } from "@/types/api";

export const YUT_LABEL: Record<YutResult, string> = { DO: "도", GAE: "개", GEOL: "걸", YUT: "윷", MO: "모" };
/** Rank is a plain integer because a store decides how many ranks it has; 1 is always the best. */
export const rankLabel = (rank?: number | null) => (rank ? `${rank}등` : "-");
export const oddsLabel = (odds?: number | null) => (odds == null ? "-" : `${odds}%`);
export const COUPON_STATUS_LABEL: Record<CouponStatus, string> = { ISSUED: "사용 가능", REDEEMED: "사용 완료", EXPIRED: "기간 만료", CANCELLED: "취소됨" };
export const PLAY_STATUS_LABEL: Record<string, string> = { CREATED: "결과 대기", REVEALED: "결과 확인", CANCELLED: "취소됨" };
export const POLICY_LABEL: Record<RedeemPolicy, string> = { ANYTIME: "즉시 사용", SAME_DAY: "당일 사용", NEXT_DAY: "다음 날부터" };

/** Falls back to the raw server value so a new enum never renders as an empty cell. */
export function labelOf(map: Record<string, string>, value?: string, fallback = "-") {
  return value ? map[value] ?? value : fallback;
}
