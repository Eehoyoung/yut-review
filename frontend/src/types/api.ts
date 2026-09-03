export type ApiError = { code: string; message: string };
export type Envelope<T> = { success: boolean; data: T | null; error: ApiError | null };
export type PageData<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number };

export type RedeemPolicy = "SAME_DAY" | "NEXT_DAY" | "ANYTIME";
export type CouponStatus = "ISSUED" | "REDEEMED" | "EXPIRED" | "CANCELLED";
export type YutResult = "DO" | "GAE" | "GEOL" | "YUT" | "MO";

export type Plan = "BASIC" | "STANDARD" | "PRO";
export type AiFeature = "AI_EVENT_COPY" | "AI_REPORT" | "AI_IMPROVEMENT" | "AI_CHAT";

export interface PlanOption {
  plan: Plan;
  monthlyPriceKrw: number;
  analyticsRetentionDays: number;
  entitlements: string[];
  aiFeatures: AiFeature[];
  automaticWeeklyReport: boolean;
  monthlyAiQuota: Partial<Record<AiFeature, number>>;
}

export interface Subscription {
  plan: Plan;
  monthlyPriceKrw: number;
  entitlements: string[];
  aiFeatures: AiFeature[];
  analyticsRetentionDays: number;
  analyticsFrom?: string;
  status?: string;
  startedAt?: string;
  note?: string;
}

export interface AiFeatureStatus {
  feature: AiFeature;
  allowed: boolean;
  used: number;
  limitPerMonth: number;
  remaining: number;
}

export interface AiStatus {
  plan: Plan;
  month: string;
  provider: string;
  features: AiFeatureStatus[];
  recentUsage: { feature: AiFeature; model: string; succeeded: boolean; createdAt: string }[];
}

export interface AiEventCopy {
  headline: string;
  subheadline: string;
  cta: string;
  posterLines: string[];
  staffGuide: string;
  policyNotice: string;
}

export interface AiReportContent {
  title: string;
  summary: string;
  highlights: { title: string; evidence: string }[];
  concerns: { title: string; evidence: string }[];
  recommendations: { action: string; reason: string; successMetric: string }[];
  dataLimitations: string[];
  window?: { from: string; to: string; clampedByPlanRetention: boolean };
}

export interface AiImprovement {
  observations: { fact: string; evidence: string }[];
  hypotheses: string[];
  experiments: { name: string; change: string; variableChanged: string; howToMeasure: string; durationDays: number }[];
  window?: { from: string; to: string; clampedByPlanRetention: boolean };
}

export interface AiChatAnswer {
  answer: string;
  toolsUsed: string[];
}

export interface Prize {
  /** 1 is the best prize. How many ranks a store has is its own configuration. */
  rank: number;
  name: string;
  description: string;
  redeemPolicy: RedeemPolicy;
  active: boolean;
}

/** Customer-facing prize entry. `odds` is computed by the server; a rank nobody can reach is not listed. */
export interface PublicPrize {
  rank: number;
  name: string;
  description: string;
  odds: number;
}

export interface GameConfigOutcome {
  yutResult: YutResult;
  weight: number;
  prizeRank: number;
  odds: number;
}

export interface GameConfig {
  rankCount: number;
  outcomes: GameConfigOutcome[];
}

export interface StoreSummary {
  id: number | string;
  name: string;
  businessNumber?: string;
  publicToken?: string;
  naverPlaceUrl?: string;
  active?: boolean;
  prizes?: PublicPrize[];
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
  prizeRank?: number;
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
  prizeRank: number;
  couponToken: string;
  prize: { name: string; description: string };
  validFrom: string;
  expiresAt: string;
}
export type RevealResponse = GameResult;
