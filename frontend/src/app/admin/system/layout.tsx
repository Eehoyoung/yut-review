import type { Metadata } from "next";

/**
 * 운영자 콘솔은 색인되지 않는다. 링크는 어디에도 걸지 않고, 주소를 아는 사람만 연다.
 * (백엔드도 같은 경로에 X-Robots-Tag를 붙인다. 화면과 API 양쪽에서 같은 답을 해야 한다.)
 */
export const metadata: Metadata = {
  title: "운영자 콘솔",
  robots: { index: false, follow: false, nocache: true },
};

export default function SystemConsoleLayout({ children }: { children: React.ReactNode }) {
  return children;
}
