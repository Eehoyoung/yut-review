import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { LandingNav } from "@/features/intro/LandingNav";
import { DEFAULT_RANK_SHARE, EXAMPLE_GROUPS, INDUSTRIES, PRESET_INFO, PRESET_ORDER, findIndustry } from "@/features/examples/industries";

// 업종 목록은 빌드 때 정해진다. 목록에 없는 주소는 404다.
export const dynamicParams = false;
export const generateStaticParams = () => INDUSTRIES.map(({ slug }) => ({ slug }));

type Props = { params: Promise<{ slug: string }> };

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const industry = findIndustry((await params).slug);
  if (!industry) return {};
  const title = `${industry.name} 이벤트 아이디어 | 소담한판 업종별 예시`;
  const url = `/examples/${industry.slug}`;
  return {
    title,
    description: industry.summary,
    alternates: { canonical: url },
    openGraph: { type: "article", locale: "ko_KR", siteName: "소담한판", url, title, description: industry.summary, images: [{ url: "/og.png", width: 1200, height: 630, alt: "소담한판" }] },
  };
}

const RANKS = [1, 2, 3] as const;

export default async function ExampleDetail({ params }: Props) {
  const industry = findIndustry((await params).slug);
  if (!industry) notFound();
  const group = EXAMPLE_GROUPS.find((g) => g.slug === industry.group)!;
  const siblings = INDUSTRIES.filter((i) => i.group === industry.group && i.slug !== industry.slug);

  return (
    <main className="landing ex-page">
      <LandingNav />
      <article className="ex-detail">
        <header className="ex-hero">
          <nav className="ex-breadcrumb" aria-label="현재 위치"><Link href="/examples">업종별 예시</Link><span aria-hidden="true">›</span><Link href={`/examples#${group.slug}`}>{group.name}</Link></nav>
          <h1>{industry.name}에서는<br />이렇게 활용해요</h1>
          <p className="ex-headline">{industry.headline}</p>
          <p>{industry.summary}</p>
          <ul className="ex-tags" aria-label="추천 목적">{industry.tags.map((t) => <li key={t}>{t}</li>)}</ul>
        </header>

        <section className="ex-block" aria-labelledby="usecase-title">
          <h2 id="usecase-title">운영 예시</h2>
          <div className="ex-usecases">
            {industry.useCases.map((u) => <div key={u.title}><h3>{u.title}</h3><p>{u.body}</p></div>)}
          </div>
        </section>

        <section className="ex-block" aria-labelledby="reward-title">
          <h2 id="reward-title">추천 경품 후보</h2>
          <ul className="ex-rewards">
            {industry.rewards.map((r) => <li key={r.name}><span>{r.name}</span><em data-revisit={r.revisit}>{r.revisit ? "다음 방문" : "바로 사용"}</em></li>)}
          </ul>
        </section>

        <section className="ex-block" aria-labelledby="preset-title">
          <h2 id="preset-title">바로 쓰는 3단계 구성</h2>
          <p className="ex-note">윷 결과 도·개는 3등, 걸·윷은 2등, 모는 1등이에요. 퍼센트는 매장 기본 확률이고 가입 후 상품 설정에서 바꿀 수 있어요.</p>
          <div className="ex-presets">
            {PRESET_ORDER.map((key) => (
              <div key={key} className="ex-preset">
                <h3>{PRESET_INFO[key].name}</h3>
                <p>{PRESET_INFO[key].body}</p>
                <ol>
                  {RANKS.map((rank) => {
                    const prize = industry.presets[key][rank];
                    return (
                      <li key={rank}>
                        <span className="ex-rank">{rank}등 <small>{DEFAULT_RANK_SHARE[rank]}%</small></span>
                        <div><b>{prize.name}</b><small>{prize.description}</small></div>
                      </li>
                    );
                  })}
                </ol>
              </div>
            ))}
          </div>
        </section>

        <section className="ex-block" aria-labelledby="tip-title">
          <h2 id="tip-title">운영 팁</h2>
          <ul className="ex-tips">{industry.tips.map((t) => <li key={t}>{t}</li>)}</ul>
          {industry.caution && <p className="ex-caution" role="note">{industry.caution}</p>}
        </section>
      </article>

      <section className="landing-final no-mascot" aria-labelledby="final-title">
        <div><h2 id="final-title">우리 매장에서도<br />바로 시작해 보세요</h2><p>가입 후 대시보드에서 업종을 검색하면 이 구성을 상품 설정에 그대로 불러올 수 있어요.</p></div>
        <div className="final-actions"><Link className="landing-choice landing-primary inverse" href="/admin/signup">30일 무료로 매장등록</Link><Link className="landing-choice landing-secondary inverse-secondary" href="/admin/login">이미 계정이 있어요</Link></div>
      </section>

      {siblings.length > 0 && (
        <nav className="ex-group ex-siblings" aria-labelledby="sibling-title">
          <h2 id="sibling-title">{group.name}의 다른 예시</h2>
          <ul className="ex-chips">{siblings.map((i) => <li key={i.slug}><Link href={`/examples/${i.slug}`}>{i.name}</Link></li>)}</ul>
        </nav>
      )}
    </main>
  );
}
