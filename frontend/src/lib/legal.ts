export const TERMS_VERSION = "2026-09-23";
export const ADMIN_PRIVACY_VERSION = "2026-09-23";
export const CUSTOMER_PRIVACY_VERSION = "2026-09-23";
export const MARKETING_SMS_VERSION = "2026-09-23";

export const marketingServices = [
  { key: "yutReviewMarketing", service: "YUT_REVIEW", name: "소담한판", description: "매장 이벤트 운영 기능, 이용 혜택과 프로모션" },
  { key: "reviewPilotMarketing", service: "REVIEW_PILOT", name: "리뷰파일럿", description: "리뷰 운영 자동화 기능, 이용 혜택과 프로모션" },
  { key: "sodamMarketing", service: "SODAM", name: "소담", description: "소상공인 매장·근무 운영 기능, 이용 혜택과 프로모션" },
] as const;

const unresolvedLegalValue = /^(replace-|사업자등록 완료 후 공개$|소담한판 운영자$)/;

function publicLegalValue(value: string | undefined, fallback: string) {
  const normalized = value?.trim();
  return normalized && !unresolvedLegalValue.test(normalized) ? normalized : fallback;
}

export const legalOperator = {
  serviceName: "소담한판",
  businessName: publicLegalValue(process.env.NEXT_PUBLIC_LEGAL_BUSINESS_NAME, "소담랩스"),
  representative: publicLegalValue(process.env.NEXT_PUBLIC_LEGAL_REPRESENTATIVE, "이호영"),
  businessNumber: publicLegalValue(process.env.NEXT_PUBLIC_LEGAL_BUSINESS_NUMBER, "358-23-02207"),
  address: publicLegalValue(process.env.NEXT_PUBLIC_LEGAL_ADDRESS, "경기도 고양시 덕양구 화정로 53-1, 709 - 가1호(화정동)"),
  email: process.env.NEXT_PUBLIC_LEGAL_EMAIL || "사업자등록 완료 후 공개",
  phone: publicLegalValue(process.env.NEXT_PUBLIC_LEGAL_PHONE, "010-9352-3827"),
};
