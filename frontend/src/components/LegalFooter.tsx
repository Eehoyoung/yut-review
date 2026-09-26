import Link from "next/link";
import { legalOperator } from "@/lib/legal";

export function LegalFooter() {
  return (
    <footer className="legal-footer" aria-label="서비스 정책 및 사업자 정보">
      <div className="legal-footer__content">
        <nav>
          <Link href="/legal/terms">이용약관</Link>
          <Link href="/legal/privacy">개인정보처리방침</Link>
          <Link href="/legal/customer-privacy">고객 개인정보 안내</Link>
          <Link href="/legal/marketing">광고성 문자 수신동의</Link>
        </nav>
        <dl className="legal-footer__business">
          <div><dt>상호</dt><dd>{legalOperator.businessName}</dd></div>
          <div><dt>대표자</dt><dd>{legalOperator.representative}</dd></div>
          <div><dt>사업자등록번호</dt><dd>{legalOperator.businessNumber}</dd></div>
          <div><dt>전화</dt><dd>{legalOperator.phone}</dd></div>
          <div className="legal-footer__address"><dt>사업장 주소</dt><dd>{legalOperator.address}</dd></div>
        </dl>
        <p>© {legalOperator.serviceName} · <a href="https://sodamlabs.kr/">{legalOperator.businessName}</a></p>
      </div>
    </footer>
  );
}
