import type { Metadata } from "next";
import { legalOperator, TERMS_VERSION } from "@/lib/legal";

export const metadata: Metadata = { title: "이용약관 | 윷리뷰" };
export const dynamic = "force-dynamic";

export default function TermsPage() {
  return (
    <main className="legal-page">
      <header><p className="brand">윷리뷰</p><h1>서비스 이용약관</h1><p className="lead">시행일 및 버전: {TERMS_VERSION}</p></header>
      <section><h2>제1조 목적</h2><p>이 약관은 {legalOperator.businessName}(이하 “회사”)가 제공하는 매장 이벤트 운영 서비스 윷리뷰의 이용 조건과 회사 및 매장 관리자의 권리·의무를 정합니다.</p></section>
      <section><h2>제2조 서비스 내용</h2><p>회사는 매장 전용 QR, 고객 참여, 서버에서 결정하는 윷 결과, 상품 설정, 쿠폰 발급·사용 처리, 참여·쿠폰 내역 및 통계 기능을 제공합니다. 리뷰 작성, 별점 또는 특정 표현은 참여나 혜택의 조건이 아닙니다.</p></section>
      <section><h2>제3조 계정과 가입</h2><p>매장 관리자는 정확한 대표자·연락처·이메일·매장·사업자 정보를 제공해야 하며 계정과 직원 PIN을 안전하게 관리해야 합니다. 타인의 정보 사용, 계정 공유로 인한 무단 접근 및 부정 이용을 금지합니다.</p></section>
      <section><h2>제4조 매장 관리자의 책임</h2><p>매장 관리자는 상품명, 사용 조건, 쿠폰 사용 가능 기간과 확률 설정을 고객이 오해하지 않도록 정확히 관리하고 약속한 상품을 제공해야 합니다. 직원 PIN은 쿠폰 사용 처리에만 사용하며, 리뷰 작성 여부를 확인하거나 긍정적인 리뷰를 요구해서는 안 됩니다.</p></section>
      <section><h2>제5조 게임과 쿠폰</h2><p>게임 결과는 회사 서버가 보안 난수와 매장 설정값을 사용해 결정합니다. 이미 발급된 쿠폰의 상품과 사용 조건은 이후 매장 설정 변경으로 바뀌지 않습니다. 고객의 재참여 제한은 매장과 정규화된 전화번호 기준 2일의 달력 날짜로 계산됩니다.</p></section>
      <section><h2>제6조 요금과 결제</h2><p>요금제, 이용료와 제공 기능은 서비스 화면 또는 별도 계약에 따릅니다. 현재 결제대행 연동 전에는 회사가 별도로 안내하고 합의한 방식으로만 요금제를 변경합니다. 유료 결제를 시작하기 전 청약철회·환불·결제 조건을 별도로 고지합니다.</p></section>
      <section><h2>제7조 금지행위</h2><p>서비스 방해, 결과·쿠폰 조작, 비정상 자동 요청, 다른 매장 데이터 접근, 개인정보 무단 수집·이용, 불법·기만적 이벤트 운영, 제3자의 권리 침해를 금지합니다.</p></section>
      <section><h2>제8조 서비스 변경·중단</h2><p>회사는 보안, 장애 대응, 법령 준수 또는 운영상 필요에 따라 서비스를 변경하거나 일시 중단할 수 있습니다. 중요한 변경이나 계획된 중단은 가능한 범위에서 사전에 알립니다.</p></section>
      <section><h2>제9조 이용 제한과 해지</h2><p>약관 위반, 보안 위협 또는 관계 법령 위반이 확인되면 회사는 사전 통지 후 이용을 제한하거나 계약을 해지할 수 있습니다. 긴급한 보안 조치가 필요한 경우 먼저 제한하고 이후 사유를 알릴 수 있습니다.</p></section>
      <section><h2>제10조 지식재산권</h2><p>서비스 프로그램, 디자인과 회사가 제작한 콘텐츠의 권리는 회사에 있습니다. 매장 관리자가 입력한 상호, 상품명과 콘텐츠의 권리 및 적법성은 해당 관리자가 책임집니다.</p></section>
      <section><h2>제11조 책임 제한</h2><p>회사는 고의 또는 중대한 과실이 없는 한 매장과 고객 사이의 상품 제공 분쟁, 매장 입력 정보의 오류, 이용자 귀책 사유로 발생한 손해에 책임을 지지 않습니다. 관련 법령상 배제할 수 없는 책임은 이 조항보다 우선합니다.</p></section>
      <section><h2>제12조 통지와 약관 변경</h2><p>회사는 서비스 화면이나 가입 이메일로 중요한 사항을 알릴 수 있습니다. 약관을 변경할 때에는 적용일과 변경 이유를 사전에 알리며, 이용자에게 불리한 중요한 변경은 필요한 동의를 다시 받습니다.</p></section>
      <section><h2>제13조 준거법과 분쟁</h2><p>대한민국 법률을 적용합니다. 분쟁이 발생하면 상호 협의를 우선하며, 해결되지 않으면 민사소송법상 관할 법원에서 해결합니다.</p></section>
      <section><h2>사업자 정보</h2><dl className="legal-facts"><div><dt>상호</dt><dd>{legalOperator.businessName}</dd></div><div><dt>대표자</dt><dd>{legalOperator.representative}</dd></div><div><dt>사업자등록번호</dt><dd>{legalOperator.businessNumber}</dd></div><div><dt>주소</dt><dd>{legalOperator.address}</dd></div><div><dt>고객 문의</dt><dd>{legalOperator.email} / {legalOperator.phone}</dd></div></dl></section>
    </main>
  );
}
