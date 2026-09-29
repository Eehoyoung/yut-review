package com.yutreview;

final class LegalConsentPolicy {
    static final String TERMS_VERSION = "2026-09-28";
    static final String ADMIN_PRIVACY_VERSION = "2026-09-29";
    static final String CUSTOMER_PRIVACY_VERSION = "2026-09-29";
    static final String MARKETING_SMS_VERSION = "2026-09-23";
    static final String BILLING_AUTO_PAYMENT_VERSION = "2026-09-29";

    private LegalConsentPolicy() {}

    static void requireAdmin(boolean termsAgreed, String termsVersion, boolean privacyAgreed, String privacyVersion) {
        if (!termsAgreed || !TERMS_VERSION.equals(termsVersion))
            throw new AppException("TERMS_CONSENT_REQUIRED", "서비스 이용약관에 동의해 주세요.");
        if (!privacyAgreed || !ADMIN_PRIVACY_VERSION.equals(privacyVersion))
            throw new AppException("PRIVACY_CONSENT_REQUIRED", "개인정보 수집·이용에 동의해 주세요.");
    }

    static void requireCustomer(boolean privacyAgreed, String privacyVersion) {
        if (!privacyAgreed || !CUSTOMER_PRIVACY_VERSION.equals(privacyVersion))
            throw new AppException("PRIVACY_CONSENT_REQUIRED", "개인정보 수집·이용에 동의해 주세요.");
    }

    /** 14세 미만의 개인정보는 법정대리인 동의 없이 받을 수 없다. 그 절차가 없으므로 참여 자체를 막는다. */
    static void requireAge(boolean ageConfirmed) {
        if (!ageConfirmed)
            throw new AppException("AGE_CONFIRMATION_REQUIRED", "만 14세 이상만 참여할 수 있어요.");
    }
}
