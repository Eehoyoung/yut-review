package com.yutreview;

final class LegalConsentPolicy {
    static final String TERMS_VERSION = "2026-09-21";
    static final String ADMIN_PRIVACY_VERSION = "2026-09-21";
    static final String CUSTOMER_PRIVACY_VERSION = "2026-09-21";
    static final String MARKETING_SMS_VERSION = "2026-09-21";

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
}
