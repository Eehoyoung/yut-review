import type { Metadata } from "next";
import { MARKETING_SMS_VERSION, legalOperator, marketingServices } from "@/lib/legal";

export const metadata: Metadata = { title: "광고성 문자 수신동의 | 소담한판" };

export default function MarketingConsentPage() {
  return <main className="legal-page">
    <header><p className="brand">소담랩스</p><h1>광고성 문자 수신동의</h1><p className="lead">버전: {MARKETING_SMS_VERSION}</p></header>
    <section><h2>선택 동의 안내</h2><p>{legalOperator.businessName}는 아래 각 서비스의 할인, 프로모션, 신규 기능과 이용 혜택을 문자메시지로 안내하기 위해 대표 연락처를 이용합니다. 각 서비스는 별도 선택 항목이며, 모두 거부해도 회원가입과 서비스 이용에 불이익이 없습니다.</p></section>
    <section><h2>서비스별 전송 범위</h2><ul>{marketingServices.map((item) => <li key={item.service}><b>{item.name}</b>: {item.description}</li>)}</ul></section>
    <section><h2>이용 항목과 보유기간</h2><ul><li>이용 항목: 대표자 휴대전화번호</li><li>이용 목적: 선택한 서비스의 영리목적 광고성 문자 전송</li><li>보유·이용 기간: 해당 서비스 수신동의 철회 또는 회원 탈퇴 시까지. 동의·철회 증적은 분쟁 대응과 법적 의무 이행에 필요한 기간 동안 별도 보관할 수 있습니다.</li></ul></section>
    <section><h2>철회 방법</h2><p>로그인 후 ‘문자 수신 설정’에서 서비스별로 즉시 철회할 수 있습니다. 실제 광고 문자에는 ‘(광고)’, 전송자 명칭과 무료 수신거부 방법을 표시합니다. 철회 처리 결과도 법령이 요구하는 방식으로 안내합니다.</p></section>
    <section><h2>문의</h2><p>{legalOperator.businessName} · 대표 {legalOperator.representative} · {legalOperator.email} · {legalOperator.phone}</p></section>
  </main>;
}
