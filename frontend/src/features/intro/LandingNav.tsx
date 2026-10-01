import Link from "next/link";
import Image from "next/image";

/** 공개 소개(/)와 업종별 예시(/examples)가 같이 쓰는 상단 메뉴. */
export function LandingNav() {
  return (
    <nav className="landing-nav" aria-label="주요 메뉴">
      <Link className="landing-wordmark" href="/" aria-label="소담한판 홈"><Image src="/brand/sodam-wordmark.webp" width={1200} height={760} alt="소담" priority /></Link>
      <div className="landing-nav-links"><Link href="/#how">이용 방법</Link><Link href="/examples">업종별 예시</Link><Link href="/#benefits">기능</Link><Link href="/#pricing">요금</Link></div>
      <Link className="landing-nav-cta" href="/admin/signup">30일 무료로 매장등록</Link>
    </nav>
  );
}
