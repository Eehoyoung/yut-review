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

export interface Summary {
  todayPlays: number;
  totalPlays: number;
  issuedCoupons: number;
  redeemedCoupons: number;
  results?: Record<string, number>;
  plan: Plan;
  advancedAvailable: boolean;
  csvExports?: string[];
}

export interface DetailedAnalytics {
  window: { from: string; to: string; clampedByPlanRetention: boolean };
  summary: { plays: number; couponsIssued: number; couponsRedeemed: number; redemptionRatePercent: number };
  hourly: { playsByHour: Record<string, number> };
  weekday: { playsByWeekday: Record<string, number> };
  prizePerformance: {
    prizes: { rank: number; prizeName: string; issued: number; redeemed: number; redemptionRatePercent: number }[];
  };
  repeat: { uniqueParticipants: number; repeatParticipants: number; repeatRatePercent: number };
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

/* ---------- 운영자 콘솔 (관리자 전용, 손님 화면은 이 타입들을 쓰지 않는다) ---------- */

export type ConsoleRole = "VIEWER" | "OPERATOR" | "OWNER";

export interface OperatorMe {
  email: string;
  name: string;
  consoleRole: ConsoleRole;
  mustChangePassword: boolean;
  sessionMinutes: number;
  idleMinutes: number;
  stepUpMinutes: number;
  stepUpFresh: boolean;
  ipRestricted: boolean;
  backupCodesRemaining: number;
  sessionExpiresAt: string;
}

export interface OperatorSession {
  status: "AUTHENTICATED";
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  email: string;
  name: string;
  consoleRole: ConsoleRole;
  mustChangePassword: boolean;
  backupCodesRemaining: number;
}

/** 2단계 인증이 아직 없는 운영자는 토큰 대신 등록 안내를 받는다. */
export interface OperatorEnrollment {
  status: "TOTP_ENROLLMENT_REQUIRED";
  enrollmentToken: string;
  secret: string;
  otpauthUrl: string;
  qrImage: string;
}

export type OperatorLoginResult = OperatorSession | OperatorEnrollment;

export interface ConsoleSession {
  id: number;
  email: string;
  current: boolean;
  device: string;
  ip: string;
  createdAt: string;
  lastSeenAt: string;
  expiresAt: string;
  revokedAt: string | null;
  revokedReason: string;
}

export interface ConsoleOperator {
  id: number;
  email: string;
  name: string;
  consoleRole: ConsoleRole;
  disabled: boolean;
  self: boolean;
  totpEnrolled: boolean;
  locked: boolean;
  lockedUntil: string | null;
  mustChangePassword: boolean;
  allowedIps: string;
  lastLoginAt: string | null;
  lastLoginIp: string;
  createdAt: string;
}

export interface PlatformOverview {
  stores: { total: number; active: number; inactive: number };
  admins: { total: number; operators: number };
  plays: { total: number; today: number };
  coupons: { issued: number; redeemed: number };
  plans: Record<string, number>;
  ai: { monthCalls: number; monthFailures: number };
  security: {
    ipRestricted: boolean;
    sessionMinutes: number;
    idleMinutes: number;
    activeSessions: number;
    failedLogins24h: number;
    operators: number;
    lockedOperators: number;
    operatorsWithoutTotp: number;
  };
}

export type StoreStatus = "ACTIVE" | "INACTIVE";

export interface ConsoleStoreRow {
  id: number;
  name: string;
  businessNumber: string;
  status: StoreStatus;
  plan: Plan;
  ownerEmail: string;
  plays: number;
  couponsIssued: number;
  couponsRedeemed: number;
  createdAt: string;
}

export interface ConsoleStoreDetail extends ConsoleStoreRow {
  phone: string;
  address: string;
  naverPlaceUrl: string;
  updatedAt: string;
  qrToken: string;
  prizeCount: number;
  rankCount: number;
  subscription: { plan: Plan; status?: string; startedAt?: string; note?: string };
}

export interface AuditEntry {
  id: number;
  actorEmail: string;
  action: string;
  targetType: string;
  targetId: number | null;
  detail: string;
  ip: string;
  succeeded: boolean;
  createdAt: string;
}

export interface AuditIntegrity {
  intact: boolean;
  checked: number;
  firstBrokenId: number | null;
  firstBrokenAt: string | null;
}
