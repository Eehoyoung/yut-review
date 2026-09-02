import type { Metadata, Viewport } from "next";
import "./globals.css";
import { Providers } from "./providers";

export const metadata: Metadata = { title: "윷 리뷰 이벤트", description: "리뷰를 확인하고 윷을 던져보세요" };
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

