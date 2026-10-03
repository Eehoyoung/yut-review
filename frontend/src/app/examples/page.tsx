import type { Metadata } from "next";
import Link from "next/link";
import { LandingNav } from "@/features/intro/LandingNav";
import { ExampleSearch } from "@/features/examples/ExampleSearch";
import { EXAMPLE_GROUPS, INDUSTRIES, POPULAR_SLUGS } from "@/features/examples/industries";

const title = "업종별 활용 예시 | 소담한판";
const description = "고깃집, 카페, 미용실, 헬스장, 세차장까지. 업종마다 잘 맞는 매장 이벤트 경품과 3단계 구성, 운영 팁을 정리했어요.";

export const metadata: Metadata = {
  title,
  description,
  alternates: { canonical: "/examples" },
  openGraph: { type: "website", locale: "ko_KR", siteName: "소담한판", url: "/examples", title, description, images: [{ url: "/og.png", width: 1200, height: 630, alt: "소담한판" }] },
};

export default function ExamplesIndex() {
  const popular = POPULAR_SLUGS.map((slug) => INDUSTRIES.find((i) => i.slug === slug)!);
  return (
    <main className="landing ex-page">
      <LandingNav />
      <header className="ex-hero">
        <p className="ex-eyebrow">업종별 활용 예시</p>
        <h1>우리 매장은 어떻게 활용할 수 있을까요?</h1>
        <p>업종을 검색하거나 아래에서 골라 보세요. 업종마다 운영 예시, 추천 경품, 바로 쓸 수 있는 3단계 구성을 담았어요.</p>
        <ExampleSearch />
        <ul className="ex-chips" aria-label="많이 찾는 업종">
          {popular.map((i) => <li key={i.slug}><Link href={`/examples/${i.slug}`}>{i.name}</Link></li>)}
        </ul>
      </header>

      <nav className="ex-groups" aria-label="업종 분류">
        {EXAMPLE_GROUPS.map((g) => <a key={g.slug} href={`#${g.slug}`}>{g.name}</a>)}
      </nav>

      {EXAMPLE_GROUPS.map((group) => (
        <section key={group.slug} id={group.slug} className="ex-group" aria-labelledby={`${group.slug}-title`}>
          <h2 id={`${group.slug}-title`}>{group.name}</h2>
          <ul className="ex-cards">
            {INDUSTRIES.filter((i) => i.group === group.slug).map((i) => (
              <li key={i.slug}>
                <Link href={`/examples/${i.slug}`}>
                  <b>{i.name}</b>
                  <span>{i.headline}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ))}

      <p className="ex-missing">찾는 업종이 없나요? 가장 비슷한 업종의 구성을 참고해 상품 설정에서 이름만 바꿔 쓰셔도 돼요.</p>
    </main>
  );
}
