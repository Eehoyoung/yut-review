import type { Metadata } from "next";

// 매장 관리자 화면(/admin)은 검색에 노출하지 않는다. 운영자 콘솔(/operator)은 자기 layout에서 같은 설정을 한다.
export const metadata: Metadata = { robots: { index: false, follow: false } };

export default function AdminLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
