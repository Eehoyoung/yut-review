import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  // 2026-10-01 운영자 콘솔을 매장 관리자 영역(/admin) 밖으로 옮겼다. 예전 북마크·메일 링크를 새 주소로 보낸다.
  // 쿼리 문자열은 Next가 그대로 붙여 준다. API(/api/admin/operator/**)는 옮기지 않고 없앴다 — 화면과 서버가 같이 배포된다.
  async redirects() {
    return [
      { source: "/admin/operator", destination: "/operator", permanent: true },
      { source: "/admin/operator/:path*", destination: "/operator/:path*", permanent: true },
    ];
  },
};

export default nextConfig;
