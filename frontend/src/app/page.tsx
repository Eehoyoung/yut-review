import type { Metadata } from "next";
import Link from "next/link";
import Image from "next/image";

// 검색 등록 대상 페이지. 문구는 코드로 확인한 기능만 쓴다(리뷰·별점은 참여 조건이 아니다).
const title = "소담한판 | QR로 참여하는 매장 재방문 이벤트";
const description = "소담한판은 손님이 매장 QR로 앱 설치 없이 윷놀이 이벤트에 참여하고, 결과에 맞는 쿠폰을 받아 매장에서 사용하는 재방문 이벤트 서비스입니다.";

export const metadata: Metadata = {
  title,
  description,
  alternates: { canonical: "/" },
  openGraph: {
    type: "website",
    locale: "ko_KR",
    siteName: "소담한판",
    url: "/",
    title,
    description,
    images: [{ url: "/og.png", width: 1200, height: 630, alt: "소담한판" }],
  },
  twitter: { card: "summary_large_image", title, description, images: ["/og.png"] },
};

const jsonLd = {
  "@context": "https://schema.org",
  "@graph": [
    {
      "@type": "WebPage",
      "@id": "https://hanpan.sodamlabs.kr/#webpage",
      url: "https://hanpan.sodamlabs.kr/",
      name: title,
      description,
      inLanguage: "ko-KR",
      about: { "@id": "https://hanpan.sodamlabs.kr/#service" },
      primaryImageOfPage: "https://hanpan.sodamlabs.kr/og.png",
    },
    {
      "@type": "Service",
      "@id": "https://hanpan.sodamlabs.kr/#service",
      name: "소담한판",
      serviceType: "매장 QR 참여 이벤트 및 쿠폰 운영",
      description: "매장별 QR로 손님이 윷놀이 이벤트에 참여하면 서버가 결과를 정하고 쿠폰을 발급하며, 직원은 PIN으로 쿠폰 사용을 처리하고 매장은 상품·확률·사용 기한을 설정합니다.",
      provider: { "@type": "Organization", "@id": "https://sodamlabs.kr/#organization", name: "소담랩스", url: "https://sodamlabs.kr/" },
      areaServed: { "@type": "Country", name: "대한민국" },
      audience: { "@type": "BusinessAudience", audienceType: "오프라인 매장 운영자" },
      url: "https://hanpan.sodamlabs.kr/",
    },
  ],
};

const journey = [
  { step: "QR 비치", detail: "테이블에 안내물을 놓습니다.", visual: "qr" },
  { step: "손님 참여", detail: "앱 설치 없이 바로 시작합니다.", visual: "phone" },
  { step: "윷 결과·쿠폰", detail: "결과에 맞는 쿠폰이 발급됩니다.", visual: "coupon" },
  { step: "직원 PIN 사용", detail: "직원은 사용 여부만 확인합니다.", visual: "pin" },
] as const;

const benefits = [
  { title: "종이 추첨보다 덜 번거롭게", body: "응모권을 나눠주고, 모으고, 추첨할 필요가 없습니다. QR 하나로 매장에서 바로 운영할 수 있습니다.", tag: "준비" },
  { title: "결과와 쿠폰을 한곳에서", body: "참여부터 결과 확인, 쿠폰 발급과 직원 사용 처리까지 한 흐름으로 이어집니다.", tag: "운영" },
  { title: "리뷰·별점은 참여 조건이 아닙니다", body: "고객의 자발적인 참여만으로 충분합니다. 긍정적인 리뷰나 특정 별점을 요구하지 않습니다.", tag: "원칙" },
] as const;

export default function Home() {
  return (
    <main className="landing">
      {/* 정적 상수만 직렬화한다. 사용자 입력이 섞이지 않으므로 </script> 주입 위험이 없다. */}
      <script type="application/ld+json" dangerouslySetInnerHTML={{ __html: JSON.stringify(jsonLd) }} />
      <nav className="landing-nav" aria-label="주요 메뉴">
        <Link className="landing-wordmark" href="/" aria-label="소담한판 홈"><Image src="/brand/sodam-wordmark.webp" width={1200} height={760} alt="소담" priority /></Link>
        <div className="landing-nav-links"><a href="#how">이용 방법</a><a href="#benefits">기능</a><a href="#pricing">요금</a></div>
        <Link className="landing-nav-cta" href="/admin/signup">14일 무료로 매장등록</Link>
      </nav>

      <section className="landing-hero" aria-labelledby="hero-title">
        <div className="hero-message">
          <h1 id="hero-title"><span className="hero-brand">소담한판</span>손님은 즐기고,<br />사장님은 <em>사용만</em><br />확인하세요</h1>
          <p>매장에 비치한 QR로 손님이 참여하면 윷 결과에 따라 쿠폰이 발급됩니다. 직원은 쿠폰을 사용할 때 PIN으로 확인하면 됩니다.</p>
          <div className="hero-actions">
            <Link className="landing-choice landing-primary" href="/admin/signup">14일 무료로 매장등록</Link>
            <Link className="landing-choice landing-secondary" href="/admin/login">이미 계정이 있어요</Link>
          </div>
          <ul className="hero-facts" aria-label="서비스 특징"><li>앱 설치 없음</li><li>직원 개입은 쿠폰 사용 때만</li><li>모든 요금제에서 게임 기능 제공</li></ul>
        </div>

        <div className="journey-stage" aria-label="QR 비치부터 쿠폰 사용까지의 운영 흐름">
          <p className="journey-note">매장에 즐거운 한 판,<br />운영은 가볍게</p>
          <ol className="journey-route">
            {journey.map((item) => (
              <li key={item.step} className={`journey-stop is-${item.visual}`}>
                <div className="journey-label"><b>{item.step}</b><span>{item.detail}</span></div>
                <div className="journey-visual" aria-hidden="true">
                  {item.visual === "qr" && <div className="mini-poster"><strong>소담한판</strong><span className="fake-qr" /><small>QR로 참여</small></div>}
                  {item.visual === "phone" && <div className="mini-phone"><small>윷을 던졌어요</small><strong>윷</strong><span>쿠폰 보기</span></div>}
                  {item.visual === "coupon" && <div className="mini-coupon"><small>당첨 쿠폰</small><strong>1등</strong><span>매장 설정 상품</span></div>}
                  {item.visual === "pin" && <div className="mini-pin"><small>직원 확인</small><div><i>•</i><i>•</i><i>•</i><i>•</i><i>•</i><i>•</i></div><strong>사용 완료</strong></div>}
                </div>
              </li>
            ))}
          </ol>
        </div>
      </section>

      <section className="landing-section benefits-section" id="benefits" aria-labelledby="benefits-title">
        <div className="section-heading"><h2 id="benefits-title">사장님의 매장을<br />더 즐거운 공간으로</h2><p>복잡한 준비 없이 매장에 바로 적용하는 참여 이벤트입니다. 손님에게는 기억에 남는 경험을, 사장님에게는 확인 가능한 운영 흐름을 남깁니다.</p></div>
        <div className="benefit-list">
          {benefits.map((benefit, index) => <article key={benefit.title} className="benefit-row"><div className={`benefit-image scene-${index + 1}`} aria-hidden="true" /><span>{benefit.tag}</span><h3>{benefit.title}</h3><p>{benefit.body}</p></article>)}
        </div>
      </section>

      <section className="landing-section how-section" id="how" aria-labelledby="how-title">
        <div className="how-copy"><h2 id="how-title">가입한 날,<br />첫 이벤트를 열 수 있습니다</h2><p>기본 상품과 확률이 준비되어 있습니다. 매장에 맞게 고치고 QR 안내물을 저장하면 시작입니다.</p><Link className="landing-text-link light" href="/admin/signup">내 매장 이벤트 만들기 <span aria-hidden="true">→</span></Link></div>
        <ol className="launch-steps">
          <li><b>1</b><div><strong>매장 등록</strong><span>계정과 첫 매장을 함께 만듭니다.</span></div></li>
          <li><b>2</b><div><strong>상품·확률 확인</strong><span>기본 설정을 그대로 쓰거나 매장에 맞게 바꿉니다.</span></div></li>
          <li><b>3</b><div><strong>QR 안내물 비치</strong><span>저장한 안내물을 테이블이나 카운터에 놓습니다.</span></div></li>
        </ol>
      </section>

      <section className="landing-section pricing-section" id="pricing" aria-labelledby="pricing-title">
        <div className="section-heading compact"><h2 id="pricing-title">게임은 어떤 요금제에서도 같습니다</h2><p>QR 이벤트, 상품·확률 설정, 쿠폰, 직원 PIN과 참여 제한은 모든 요금제에 포함됩니다. 차이는 기록 보관 기간과 브랜딩·AI 운영 기능입니다.</p></div>
        <div className="price-line" aria-label="월 요금제">
          <div><span>BASIC</span><strong>9,900원<small>/월</small></strong><em>기본 집계·AI 매장 분석</em></div>
          <div><span>STANDARD</span><strong>14,900원<small>/월</small></strong><em>365일 기록·CSV</em></div>
          <div className="recommended"><span>PRO</span><strong>19,900원<small>/월</small></strong><em>브랜딩·AI 운영 지원</em></div>
        </div>
        <p className="pricing-note">신규 매장은 가입일부터 14일 동안 PRO 기능을 무료로 사용합니다. 결제가 없으면 15일째 BASIC으로 전환되며 자동 결제되지 않습니다.</p>
      </section>

      <section className="landing-final" aria-labelledby="final-title">
        <div><h2 id="final-title">이번 주 매장 이벤트,<br />QR 한 장으로 시작하세요</h2><p>직원에게 새로운 일을 늘리지 않고 손님에게 다시 찾을 이유를 만듭니다.</p></div>
        <Image className="landing-mascot" src="/brand/sodam-mascot.webp" width={1122} height={1402} alt="손을 흔드는 소담 캐릭터" />
        <div className="final-actions"><Link className="landing-choice landing-primary inverse" href="/admin/signup">14일 무료로 매장등록</Link><Link className="landing-choice landing-secondary inverse-secondary" href="/admin/login">이미 계정이 있어요</Link></div>
      </section>
    </main>
  );
}
