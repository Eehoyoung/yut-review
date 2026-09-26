import type { Metadata } from "next";

// 매장별 QR 참여 화면은 매장 토큰이 URL에 들어가므로 검색에 노출하지 않는다.
export const metadata: Metadata = { robots: { index: false, follow: false } };

export default function StoreLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
