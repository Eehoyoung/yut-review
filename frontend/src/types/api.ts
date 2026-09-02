export type ApiError = { code: string; message: string };
export type Envelope<T> = { success: boolean; data: T | null; error: ApiError | null };

export type PrizeTier = "TIER_1" | "TIER_2" | "TIER_3";
export type RedeemPolicy = "SAME_DAY" | "NEXT_DAY" | "ANYTIME";
export type CouponStatus = "ISSUED" | "REDEEMED" | "EXPIRED" | "CANCELLED";
export type YutResult = "DO" | "GAE" | "GEOL" | "YUT" | "MO";

export interface Prize {
  tier: PrizeTier;
  name: string;
  description: string;
  redeemPolicy: RedeemPolicy;
  active: boolean;
}

export interface StoreSummary {
  id: number | string;
  name: string;
  publicToken?: string;
  naverPlaceUrl?: string;
  active?: boolean;
  prizes?: Prize[];
}

export type CustomerState = {
  state: "HAS_ACTIVE_COUPON" | "CAN_PLAY" | "COOLDOWN";
  couponToken?: string;
  nextPlayableDate?: string;
};

export interface Coupon {
  couponToken?: string;
  token?: string;
  status: CouponStatus;
  prizeName?: string;
  prizeDescription?: string;
  prize?: { name: string; description: string };
  validFrom: string;
  expiresAt: string;
  redeemedAt?: string | null;
}

export interface GameCreated {
  playId: string;
  animationSeed: string;
  animationProfile: string;
}

export interface GameResult {
  playId: string;
  yutResult: YutResult;
  tier: PrizeTier;
  couponToken: string;
  prize: { name: string; description: string };
  validFrom: string;
  expiresAt: string;
}
export type RevealResponse = GameResult;
