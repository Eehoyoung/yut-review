import type { MetadataRoute } from "next";

// 검색 등록 대상은 공개 소개 페이지 하나뿐이다. /legal/*은 색인은 허용하되 사이트맵에는 넣지 않는다.
export default function sitemap(): MetadataRoute.Sitemap {
  return [{ url: "https://hanpan.sodamlabs.kr/" }];
}
