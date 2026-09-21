export const TERMS_VERSION = "2026-09-21";
export const ADMIN_PRIVACY_VERSION = "2026-09-21";
export const CUSTOMER_PRIVACY_VERSION = "2026-09-21";
export const MARKETING_SMS_VERSION = "2026-09-21";

export const marketingServices = [
  { key: "yutReviewMarketing", service: "YUT_REVIEW", name: "윷리뷰", description: "매장 이벤트 운영 기능, 이용 혜택과 프로모션" },
  { key: "reviewPilotMarketing", service: "REVIEW_PILOT", name: "리뷰파일럿", description: "리뷰 운영 자동화 기능, 이용 혜택과 프로모션" },
  { key: "sodamMarketing", service: "SODAM", name: "소담", description: "소상공인 매장·근무 운영 기능, 이용 혜택과 프로모션" },
] as const;

export const legalOperator = {
  serviceName: "윷리뷰",
  businessName: process.env.NEXT_PUBLIC_LEGAL_BUSINESS_NAME || "윷리뷰 운영자",
  representative: process.env.NEXT_PUBLIC_LEGAL_REPRESENTATIVE || "사업자등록 완료 후 공개",
  businessNumber: process.env.NEXT_PUBLIC_LEGAL_BUSINESS_NUMBER || "사업자등록 완료 후 공개",
  address: process.env.NEXT_PUBLIC_LEGAL_ADDRESS || "사업자등록 완료 후 공개",
  email: process.env.NEXT_PUBLIC_LEGAL_EMAIL || "사업자등록 완료 후 공개",
  phone: process.env.NEXT_PUBLIC_LEGAL_PHONE || "사업자등록 완료 후 공개",
};
