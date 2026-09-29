export const TERMS_VERSION = "2026-09-28";
export const BILLING_POLICY_VERSION = "2026-09-28";
export const BILLING_AUTO_PAYMENT_VERSION = "2026-09-29";
export const ADMIN_PRIVACY_VERSION = "2026-09-29";
export const CUSTOMER_PRIVACY_VERSION = "2026-09-29";
export const MARKETING_SMS_VERSION = "2026-09-23";

export const marketingServices = [
  { key: "yutReviewMarketing", service: "YUT_REVIEW", name: "소담한판", description: "매장 이벤트 운영 기능, 이용 혜택과 프로모션" },
  { key: "reviewPilotMarketing", service: "REVIEW_PILOT", name: "리뷰파일럿", description: "리뷰 운영 자동화 기능, 이용 혜택과 프로모션" },
  { key: "sodamMarketing", service: "SODAM", name: "소담", description: "소상공인 매장·근무 운영 기능, 이용 혜택과 프로모션" },
] as const;

const unresolvedLegalValue = /^(replace-|사업자등록 완료 후 공개$|통신판매업 신고 완료 후 공개$|소담한판 운영자$|미정$|pending$)/i;

function publicLegalValue(value: string | undefined, fallback: string) {
  const normalized = value?.trim();
  return normalized && !unresolvedLegalValue.test(normalized) ? normalized : fallback;
}

function optionalPublicLegalValue(value: string | undefined) {
  const normalized = value?.trim();
  return normalized && !unresolvedLegalValue.test(normalized) ? normalized : undefined;
}

function publicLegalEmail(value: string | undefined, fallback: string) {
  const normalized = value?.trim();
  return normalized &&
    !unresolvedLegalValue.test(normalized) &&
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized)
    ? normalized
    : fallback;
}

export const legalOperator = {
  serviceName: "소담한판",
  businessName: publicLegalValue(process.env.NEXT_PUBLIC_LEGAL_BUSINESS_NAME, "소담랩스"),
  representative: publicLegalValue(process.env.NEXT_PUBLIC_LEGAL_REPRESENTATIVE, "이호영"),
  businessNumber: publicLegalValue(process.env.NEXT_PUBLIC_LEGAL_BUSINESS_NUMBER, "358-23-02207"),
  address: publicLegalValue(process.env.NEXT_PUBLIC_LEGAL_ADDRESS, "경기도 고양시 덕양구 화정로 53-1, 709 - 가1호(화정동)"),
  email: publicLegalEmail(process.env.NEXT_PUBLIC_LEGAL_EMAIL, "sodamlabs@gmail.com"),
  phone: publicLegalValue(process.env.NEXT_PUBLIC_LEGAL_PHONE, "010-9352-3827"),
  // NEXT_PUBLIC_*는 빌드 때 번들에 박힌다. 운영 이미지는 GHCR에서 이 값 없이 빌드되므로
  // 신고번호는 환경 변수가 아니라 여기 상수로 넣고 이미지를 다시 게시한다. 비어 있으면 표시하지 않는다.
  mailOrderReportNumber: optionalPublicLegalValue(""),
  mailOrderReportAuthority: optionalPublicLegalValue(""),
};
