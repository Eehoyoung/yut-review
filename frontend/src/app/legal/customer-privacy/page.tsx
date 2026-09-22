import type { Metadata } from "next";
import { CUSTOMER_PRIVACY_VERSION, legalOperator } from "@/lib/legal";

export const metadata: Metadata = { title: "고객 개인정보 수집·이용 안내 | 소담한판" };
export const dynamic = "force-dynamic";

export default function CustomerPrivacyPage() {
  return (
    <main className="legal-page">
      <header><p className="brand">소담한판</p><h1>고객 개인정보 수집·이용 안내</h1><p className="lead">동의문 버전: {CUSTOMER_PRIVACY_VERSION}</p></header>
      <section><h2>누가 처리하나요?</h2><p>참여한 이벤트의 운영 매장과 서비스 운영자 {legalOperator.businessName}가 참여 확인과 쿠폰 제공을 위해 처리합니다. 매장 관리자는 자기 매장의 참여·쿠폰 정보만 확인할 수 있습니다.</p></section>
      <section><h2>수집 항목</h2><p>이름, 휴대폰 번호, 참여 매장, 게임 결과, 당첨 상품과 쿠폰 상태, 참여·사용 시각</p></section>
      <section><h2>이용 목적</h2><p>중복 참여 방지, 기존 쿠폰 확인, 윷놀이 결과와 쿠폰 제공, 쿠폰 사용 처리, 부정 이용 방지</p></section>
      <section><h2>보유 기간</h2><p>참여일을 포함해 120일간 보관한 뒤 개인정보를 복구 불가능하게 익명화합니다. 유효한 미사용 쿠폰이 있으면 쿠폰 만료 후 익명화합니다.</p></section>
      <section><h2>동의를 거부할 권리</h2><p>동의를 거부할 수 있습니다. 다만 이름과 휴대폰 번호가 없으면 참여 제한과 기존 쿠폰을 확인할 수 없어 이벤트에 참여할 수 없습니다.</p></section>
      <section><h2>보호 방법</h2><p>이름과 전화번호는 암호화하며, 중복 참여 확인용 전화번호 값은 별도의 키로 보호합니다. 전체 전화번호는 매장 관리자 화면에 표시하지 않습니다.</p></section>
    </main>
  );
}
