"use client";
import Link from "next/link";
import { useParams, usePathname } from "next/navigation";

const menus = [
  ["dashboard", "대시보드"],
  ["prizes", "상품 설정"],
  ["qr", "QR 안내물"],
  ["staff-pin", "직원 PIN"],
  ["game-plays", "참여 내역"],
  ["coupons", "쿠폰 내역"],
  ["analytics", "통계"],
  ["ai", "AI 도우미"],
  ["plan", "요금제"],
];

export function AdminFrame({ title, children }: { title: string; children: React.ReactNode }) {
  const id = String(useParams().storeId);
  const pathname = usePathname();
  return (
    <main className="admin-shell">
      <header className="admin-head">
        <h1>{title}</h1>
        <Link className="btn secondary btn-inline" href="/admin">
          매장 변경
        </Link>
      </header>
      {/* 메뉴가 일곱 개라 지금 어디에 있는지 표시가 없으면 방향을 잃는다. */}
      <nav className="nav" aria-label="매장 관리 메뉴">
        {menus.map(([path, label]) => {
          const href = `/admin/stores/${id}/${path}`;
          const current = pathname === href;
          return (
            <Link key={path} href={href} aria-current={current ? "page" : undefined}>
              {label}
            </Link>
          );
        })}
      </nav>
      {children}
    </main>
  );
}
