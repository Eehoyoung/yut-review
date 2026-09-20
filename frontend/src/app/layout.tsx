import type { Metadata, Viewport } from "next";
// 한글 웹폰트는 반드시 자체 호스팅한다. Nginx CSP가 font-src/style-src를 'self'로 묶어서
// CDN을 쓰면 조용히 무시되고 폴백된다. 이 CSS는 unicode-range로 92조각을 나눠 두어
// 브라우저가 화면에 실제로 쓰인 글자 범위만 내려받는다.
import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "./globals.css";
import { Providers } from "./providers";

export const metadata: Metadata = { title: "윷 리뷰 이벤트", description: "리뷰 쓰고 윷 한 판" };
// 노치/홈 인디케이터가 있는 폰에서 화면 끝까지 쓰되, 안전영역만큼 콘텐츠를 띄운다.
export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: "#f4f7f5",
};
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="ko"><body><Providers>{children}</Providers></body></html>;
}
