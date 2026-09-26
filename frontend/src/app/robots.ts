import type { MetadataRoute } from "next";

// 공개 소개(/)는 색인한다. /admin, /s/는 메타 robots noindex로 빼므로 여기서 막지 않는다
// (막으면 크롤러가 noindex를 못 보고 URL만 색인될 수 있다). API만 막는다.
export default function robots(): MetadataRoute.Robots {
  return {
    rules: { userAgent: "*", allow: "/", disallow: ["/api/"] },
    sitemap: "https://hanpan.sodamlabs.kr/sitemap.xml",
  };
}
