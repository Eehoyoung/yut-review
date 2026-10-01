import type { MetadataRoute } from "next";
import { INDUSTRIES } from "@/features/examples/industries";

// 검색 등록 대상은 공개 소개 페이지와 업종별 예시다. /legal/*은 색인은 허용하되 사이트맵에는 넣지 않는다.
const origin = "https://hanpan.sodamlabs.kr";

export default function sitemap(): MetadataRoute.Sitemap {
  return [
    { url: `${origin}/` },
    { url: `${origin}/examples` },
    ...INDUSTRIES.map(({ slug }) => ({ url: `${origin}/examples/${slug}` })),
  ];
}
