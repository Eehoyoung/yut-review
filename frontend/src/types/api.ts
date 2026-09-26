export type ApiError = { code: string; message: string };
export type Envelope<T> = { success: boolean; data: T | null; error: ApiError | null };
export type PageData<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number };

export type RedeemPolicy = "SAME_DAY" | "NEXT_DAY" | "ANYTIME";
export type CouponStatus = "ISSUED" | "REDEEMED" | "EXPIRED" | "CANCELLED";
export type YutResult = "DO" | "GAE" | "GEOL" | "YUT" | "MO";

export type Plan = "BASIC" | "STANDARD" | "PRO";
export type AiFeature = "AI_EVENT_COPY" | "AI_REPORT" | "AI_IMPROVEMENT" | "AI_CHAT";
export type MarketingService = "YUT_REVIEW" | "REVIEW_PILOT" | "SODAM";
export interface MarketingConsent { service: MarketingService; serviceName: string; agreed: boolean; version: string; changedAt: string | null }

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
  trial?: boolean;
  trialEndsAt?: string;
  note?: string;
}

export interface BillingChannel {
  channelKey: string;
  pg: "TOSSPAYMENTS" | "INICIS";
}

export interface BillingPayment {
  plan: Plan;
  amount: number;
  status: "PENDING" | "PAID" | "FAILED";
  createdAt: string;
  failureReason: string;
}

export interface Billing {
  enabled: boolean;
  portoneStoreId: string;
  customer: { customerId: string; fullName: string; email: string; phoneNumber: string };
  channels: BillingChannel[];
  /** 날짜에서 계산한 이용 상태. RESTRICTED면 손님 화면과 사장 운영 API가 막혀 있다. */
  serviceState: "OPEN" | "TRIAL" | "ACTIVE" | "GRACE" | "RESTRICTED";
  lastPaidAt?: string;
  nextBillingAt?: string;
  /** 이 시각부터 이용 제한(결제예정일 D+3 00:00, KST). */
  restrictedFrom?: string;
  hasCard?: boolean;
  autoRenew?: boolean;
  nextPlan?: Plan | null;
  pg?: BillingChannel["pg"] | null;
  renewalFailures?: number;
  payments: BillingPayment[];
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
  liveProviderReady: boolean;
  models: { fast: string; analysis: string; chat: string };
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
  comparedToPrevious?: {
    current: AnalyticsPeriod;
    previous: AnalyticsPeriod | null;
    note?: string;
    playsChange?: number;
    playsChangePercent?: number | null;
  };
  hourly: { playsByHour: Record<string, number> };
  weekday: { playsByWeekday: Record<string, number> };
  prizePerformance: {
    prizes: { prizeRank: number; prizeName: string; issued: number; redeemed: number; redemptionRatePercent: number }[];
  };
  repeat: { uniqueParticipants: number; repeatParticipants: number; repeatRatePercent: number };
}

export interface AnalyticsPeriod {
  from: string;
  to: string;
  days: number;
  plays: number;
  couponsIssued: number;
  couponsRedeemed: number;
  redemptionRatePercent: number;
  couponsByStatus: Record<string, number>;
}

export interface AiChatAnswer {
  answer: string;
  toolsUsed: string[];
}

/** 대화 이력은 서버가 소유한다. 화면은 읽어서 보여 주기만 한다. */
export interface AiChatTurn {
  role: "user" | "assistant";
  content: string;
  createdAt: string;
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

/** 매장별 이벤트 설정. 범위는 서버가 함께 내려주므로 화면이 상수를 따로 들고 있지 않는다. */
export interface EventSettings {
  couponValidityDays: number;
  minDays: number;
  maxDays: number;
  defaultDays: number;
}

/** 매장은 운영자 승인을 거쳐야 ACTIVE가 된다. 기존 매장은 전부 ACTIVE로 남는다. */
export type StoreStatus = "PENDING_APPROVAL" | "ACTIVE" | "INACTIVE" | "REJECTED";

export interface StoreSummary {
  id: number | string;
  name: string;
  businessNumber?: string;
  publicToken?: string;
  naverPlaceUrl?: string;
  posterTagline?: string;
  status?: StoreStatus;
  /** 거부 사유가 있을 때만 채워진다. */
  approvalNote?: string;
  prizes?: PublicPrize[];
}

export interface AdminMe {
  id: number;
  email: string;
  name: string;
  role: "SYSTEM_ADMIN" | "STORE_ADMIN";
  /** 지인에게 알려 주는 코드. 계정당 하나이고 바뀌지 않는다. */
  inviteCode: string;
  /** 이 코드로 가입한 사람 수. 리워드 정책은 아직 없고 숫자만 보여 준다. */
  invitedCount: number;
}

export interface OperatorSummary {
  pending: number;
  active: number;
  rejected: number;
}

export interface OperatorStore {
  id: number;
  name: string;
  businessNumber: string;
  ownerName: string;
  ownerEmail: string;
  ownerPhone: string;
  status: StoreStatus;
  createdAt: string;
  note: string;
}

/** 운영자 자원 현황. 집계와 매장 공개 라벨뿐이고 고객 개인정보는 들어 있지 않다. */
export interface OperatorMonitoring {
  date: string;
  /** 코드별 최근 24시간 차단 수. 0인 코드도 내려온다. */
  throttled: Record<string, number>;
  counters: { rows: number; maxRows: number };
  limits: { gamePerStorePerMinute: number; gamePerIpPerMinute: number; gamePerStorePerDay: number };
  storage: {
    gamePlays: number;
    coupons: number;
    recoverySessions: number;
    aiChatTurns: number;
    stores: number;
    posterBase64Chars: number;
  };
  busiestStores: { storeId: number; name: string; playsToday: number; dailyLimit: number; usedPercent: number }[];
}

/** 관리자 계정. 비밀번호 해시는 서버가 내려보내지 않는다. */
export interface OperatorAdmin {
  id: number;
  email: string;
  name: string;
  role: "SYSTEM_ADMIN" | "STORE_ADMIN";
  /** 이 계정이 속한 매장 수. 운영자는 0이어야 정상이다. */
  storeCount: number;
  createdAt: string;
}

/** 매장 심사와 계정 변경을 시간순으로 합친 활동 피드의 한 줄. */
export interface OperatorAuditEntry {
  kind: "STORE" | "ACCOUNT";
  action:
    | "APPROVE"
    | "REJECT"
    | "REVIEW_AGAIN"
    | "OWNERSHIP_CHANGE"
    | "OPERATOR_CREATED"
    | "OPERATOR_GRANTED"
    | "OPERATOR_REVOKED";
  actor: string | null;
  target: string | null;
  storeId?: number | null;
  note: string | null;
  createdAt: string;
}

export interface ApprovalEvent {
  action: "APPROVE" | "REJECT" | "REVIEW_AGAIN" | "OWNERSHIP_CHANGE";
  actorEmail: string;
  note: string;
  createdAt: string;
}

/**
 * `couponToken`은 더 이상 여기로 오지 않는다. HAS_ACTIVE_COUPON이면 1회용·5분 만료
 * `recoveryTicket`을 받아 `coupons/recover`로 교환한다.
 */
export type CustomerState = {
  state: "HAS_ACTIVE_COUPON" | "CAN_PLAY" | "COOLDOWN";
  recoveryTicket?: string;
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
