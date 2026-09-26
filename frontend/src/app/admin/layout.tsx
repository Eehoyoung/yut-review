import type { Metadata } from "next";

// 관리자 화면은 검색에 노출하지 않는다.
export const metadata: Metadata = { robots: { index: false, follow: false } };

export default function AdminLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
