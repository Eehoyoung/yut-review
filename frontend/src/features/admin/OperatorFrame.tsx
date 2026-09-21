"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";

/**
 * 운영자 콘솔 공통 껍데기.
 *
 * `AdminFrame`과 같은 모양이지만 합치지 않는다. 저쪽은 매장 하나 안을 도는 메뉴라 `storeId`가
 * 반드시 있고, 이쪽은 매장 밖에서 도는 메뉴라 없다. 하나로 합치면 `storeId`가 선택값이 되고,
 * 그 선택값을 잊은 화면이 조용히 다른 매장을 가리키게 된다.
 */
const menus: [string, string][] = [
  ["", "매장 심사"],
  ["accounts", "계정"],
  ["resources", "자원 현황"],
  ["audit", "활동 기록"],
  ["devices", "기기"],
];

export function OperatorFrame({ title, children }: { title: string; children: React.ReactNode }) {
  const pathname = usePathname();

  return (
    <main className="admin-shell">
      <header className="admin-head">
        <div>
          <p className="brand">소담랩스 운영자</p>
          <h1>{title}</h1>
        </div>
        <Link className="btn ghost btn-inline" href="/admin">
          내 매장
        </Link>
      </header>

      <nav className="nav" aria-label="운영자 메뉴">
        {menus.map(([path, label]) => {
          const href = path ? `/admin/operator/${path}` : "/admin/operator";
          return (
            <Link key={label} href={href} aria-current={pathname === href ? "page" : undefined}>
              {label}
            </Link>
          );
        })}
      </nav>

      {children}
    </main>
  );
}
