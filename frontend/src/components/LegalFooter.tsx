import Link from "next/link";

export function LegalFooter() {
  return (
    <footer className="legal-footer" aria-label="서비스 정책">
      <nav>
        <Link href="/legal/terms">이용약관</Link>
        <Link href="/legal/privacy">개인정보처리방침</Link>
        <Link href="/legal/customer-privacy">고객 개인정보 안내</Link>
        <Link href="/legal/marketing">광고성 문자 수신동의</Link>
      </nav>
      <p>© 윷리뷰</p>
    </footer>
  );
}
