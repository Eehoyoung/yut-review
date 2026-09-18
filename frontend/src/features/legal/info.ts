export const LEGAL_INFO = {
  operatorName: "소담랩스",
  serviceName: "Yut Review",
  representative: "[출시 전 입력: 대표자명]",
  businessRegistrationNumber: "[출시 전 입력: 사업자등록번호]",
  mailOrderRegistrationNumber: "[출시 전 입력: 통신판매업 신고번호]",
  businessAddress: "[출시 전 입력: 사업장 주소]",
  customerServiceEmail: "[출시 전 입력: 고객센터 이메일]",
  customerServicePhone: "[출시 전 입력: 고객센터 전화번호]",
  privacyOfficer: "[출시 전 입력: 개인정보 보호책임자]",
  effectiveDate: "[출시 전 입력: 시행일]",
} as const;

/**
 * 법적 고지에 필요한 사업자 정보가 확정되기 전까지 placeholder를 유지한다.
 * LEGAL_RELEASE_CHECKLIST.md의 Merge Blocker를 모두 해소하기 전에는 출시하지 않는다.
 */
export const LEGAL_INFO_HAS_PLACEHOLDERS = Object.values(LEGAL_INFO).some((value) =>
  value.startsWith("[출시 전 입력:")
);
