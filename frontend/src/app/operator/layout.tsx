import type { Metadata } from "next";

// 운영자 콘솔(/operator)은 검색에 노출하지 않는다. 매장 관리자 화면(/admin)과 같은 설정이다.
export const metadata: Metadata = { robots: { index: false, follow: false } };

export default function OperatorLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
