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
  plan: Plan | null;
  serviceState: "OPEN" | "TRIAL" | "ACTIVE" | "GRACE" | "RESTRICTED";
  monthlyPriceKrw: number;
  entitlements: string[];
  aiFeatures: AiFeature[];
  analyticsRetentionDays: number;
  analyticsFrom?: string;
  status?: string;
  startedAt?: string;
  trial?: boolean;
  trialEndsAt?: string;
  trialStartedAt?: string;
  trialEndedAt?: string;
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
  /** 지금 이 요금제를 선택할 때 즉시 청구될 금액. 체험/하향은 0원, 유료기간 상향은 일할 차액. */
  checkoutAmounts?: Partial<Record<Plan, number>>;
  /** 즉시 청구액이 잔여기간 일할 차액인지 여부. */
  checkoutProrated?: Partial<Record<Plan, boolean>>;
  /** 선택한 요금제로 카드 등록/결제한 뒤 다음 자동결제 예정시각. */
  checkoutNextBillingAt?: Partial<Record<Plan, string>>;
  /** 서버가 실제 동의 증적에 저장하는 것과 동일한 자동결제 동의문. */
  checkoutConsentTexts?: Partial<Record<Plan, string>>;
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

/**
 * 계정 역할. 서버 `AdminRole`. OPERATOR = 소담랩스 운영자(/operator), STORE_ADMIN = 매장 관리자(/admin).
 * 2026-10-01 전에는 운영자가 "SYSTEM_ADMIN"이었다.
 */
export type AccountRole = "OPERATOR" | "STORE_ADMIN";

export interface AdminMe {
  id: number;
  email: string;
  name: string;
  role: AccountRole;
  /** 지인에게 알려 주는 코드. 계정당 하나이고 바뀌지 않는다. */
  inviteCode: string;
  /** 이 코드로 가입한 사람 수. 리워드 정책은 아직 없고 숫자만 보여 준다. */
  invitedCount: number;
}

export interface OperatorSummary {
  pending: number;
  active: number;
  inactive: number;
  rejected: number;
  /** 서버 설정 `STORE_APPROVAL_REQUIRED`. false면 가입 즉시 운영 중이 된다. */
  approvalRequired: boolean;
}

export type ServiceState = "OPEN" | "TRIAL" | "ACTIVE" | "GRACE" | "RESTRICTED";

/** 매장 목록 한 행의 숫자. 요금제 행이 없는 매장은 plan/serviceState가 없다. */
export interface OperatorStoreStats {
  gamesToday: number;
  gamesTotal: number;
  couponsIssued: number;
  couponsRedeemed: number;
  lastPlayedAt: string | null;
  plan?: Plan;
  serviceState?: ServiceState;
}

/** 운영자 현황판. 집계와 매장·사장 정보뿐이고 고객 개인정보는 없다. */
export interface OperatorOverview {
  date: string;
  approvalRequired: boolean;
  today: { signups: number; games: number; couponsIssued: number; couponsRedeemed: number };
  totals: {
    stores: number;
    activeStores: number;
    pendingStores: number;
    games: number;
    couponsIssued: number;
    couponsRedeemed: number;
  };
  trend: { date: string; games: number; couponsRedeemed: number; signups: number }[];
  recentSignups: {
    id: number;
    name: string;
    status: StoreStatus;
    createdAt: string;
    businessVerified: boolean;
    ownerName: string;
    ownerEmail: string;
  }[];
  topStoresToday: { storeId: number; name: string; games: number; couponsRedeemed: number }[];
}

export interface OperatorSessionPolicy {
  otpTtlSeconds: number;
  sessionTtlSeconds: number;
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
  businessVerifiedAt: string | null;
  note: string;
  stats: OperatorStoreStats;
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

/** 운영자 콘솔 계정 목록의 한 행(운영자와 매장 관리자 모두). 비밀번호 해시는 서버가 내려보내지 않는다. */
export interface OperatorAdmin {
  id: number;
  email: string;
  name: string;
  role: AccountRole;
  /** 이 계정이 속한 매장 수. 운영자는 0이어야 정상이다. */
  storeCount: number;
  createdAt: string;
}

/** 매장 심사와 계정 변경을 시간순으로 합친 활동 피드의 한 줄. */
export interface OperatorAuditEntry {
  kind: "STORE" | "ACCOUNT" | "SUPPORT";
  action:
    | "APPROVE"
    | "REJECT"
    | "REVIEW_AGAIN"
    | "OWNERSHIP_CHANGE"
    | "OPERATOR_CREATED"
    | "OPERATOR_GRANTED"
    | "OPERATOR_REVOKED"
    | StoreCareAction;
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

/** 입점 키트 실물 발송 상태. 서버 `PrintKitStatus`. 행이 없는 매장은 WAITING으로 온다. */
export type PrintKitStatus = "WAITING" | "PRINTING" | "PRINTED" | "SHIPPED";
export interface PrintKitRow {
  storeId: number;
  name: string;
  businessNumber: string;
  representativeName: string;
  storePhone: string;
  address: string;
  createdAt: string;
  ownerName: string;
  ownerEmail: string;
  ownerPhone: string;
  status: PrintKitStatus;
  statusUpdatedAt: string | null;
  statusUpdatedBy: string;
}
export type PrintKitPage = PageData<PrintKitRow> & { counts: Record<PrintKitStatus, number> };

/** 운영자가 매장에 해 준 일(`operator_store_actions`). 매장 운영 설정은 여기에 없다. */
export type StoreCareAction =
  | "PROFILE_UPDATED"
  | "BILLING_POSTPONED"
  | "COMPLIMENTARY_PLAN_GRANTED"
  | "COMPLIMENTARY_PLAN_ENDED"
  | "STATS_EXPORTED"
  | "STAFF_PIN_RESET";
export type SubscriptionPaymentStatus = "PENDING" | "PAID" | "FAILED";

export interface OperatorPayment {
  id: number;
  paymentId: string;
  storeId: number;
  storeName: string;
  plan: Plan;
  amount: number;
  status: SubscriptionPaymentStatus;
  failureReason: string;
  createdAt: string;
  paidAt: string | null;
}

/** 기능 등급(effectivePlan)과 청구 등급(billingPlan)이 다를 수 있다 — 보상 등급은 청구에 쓰이지 않는다. */
export interface OperatorSubscription {
  billingPlan: Plan;
  effectivePlan: Plan;
  serviceState: ServiceState;
  billingTarget: boolean;
  monthlyPriceKrw?: number;
  complimentaryPlan?: Plan;
  complimentaryUntil?: string;
  trialEndsAt?: string;
  nextBillingAt?: string;
  restrictedFrom?: string;
  lastPaidAt?: string;
  nextPlan?: Plan | null;
  autoRenew?: boolean;
  hasCard?: boolean;
  pg?: string | null;
  renewalFailures?: number;
  note?: string;
}

export interface OperatorStoreCare {
  store: {
    id: number;
    name: string;
    status: StoreStatus;
    createdAt: string;
    businessNumber: string;
    representativeName: string;
    openingDate: string;
    businessVerifiedAt: string | null;
    phone: string;
    address: string;
    naverPlaceUrl: string;
  };
  members: { name: string; email: string; phone: string; role: "OWNER" | "MANAGER"; since: string }[];
  subscription: OperatorSubscription;
  payments: OperatorPayment[];
  usage: OperatorStoreStats & {
    last30Days: { from: string; to: string; plays: number; couponsIssued: number; couponsRedeemed: number; redemptionRatePercent: number };
  };
  careLog: { action: StoreCareAction; actor: string; reason: string; detail: string; createdAt: string }[];
}

export interface OperatorMetrics {
  date: string;
  customers: { dau: number; wau: number; mau: number };
  stores: { total: number; active: number; pending: number; engaged30: number; newThisMonth: number };
  revenue: {
    mrr: number;
    arr: number;
    payingStores: number;
    arpu: number;
    paidThisMonth: number;
    paidLastMonth: number;
    failedThisMonth: number;
    pendingToReconcile: number;
  };
  plans: Record<Plan, number>;
  serviceStates: Record<ServiceState, number>;
  health: {
    complimentary: number;
    autoRenewOff: number;
    renewalFailing: number;
    trialEnded: number;
    trialConverted: number;
    trialConversionPercent: number | null;
  };
  engagementThisMonth: {
    games: number;
    couponsIssued: number;
    couponsRedeemed: number;
    redemptionPercent: number;
    gamesPerEngagedStore: number;
  };
  monthly: {
    month: string;
    signups: number;
    games: number;
    couponsIssued: number;
    couponsRedeemed: number;
    revenue: number;
    payments: number;
    mau: number | null;
  }[];
  daily: {
    date: string;
    dau: number;
    wau: number;
    mau: number;
    games: number;
    couponsIssued: number;
    couponsRedeemed: number;
    signups: number;
    engagedStores: number;
    payingStores: number | null;
    mrr: number | null;
  }[];
}
