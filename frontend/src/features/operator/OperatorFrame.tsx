"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { LogoutButton } from "@/features/admin/LogoutButton";
import { clearAdminSession } from "@/lib/api";

/**
 * 운영자 콘솔 공통 껍데기.
 *
 * `AdminFrame`과 같은 모양이지만 합치지 않는다. 저쪽은 매장 하나 안을 도는 메뉴라 `storeId`가
 * 반드시 있고, 이쪽은 매장 밖에서 도는 메뉴라 없다. 하나로 합치면 `storeId`가 선택값이 되고,
 * 그 선택값을 잊은 화면이 조용히 다른 매장을 가리키게 된다.
 *
 * 운영자는 거의 노트북 브라우저로 들어온다. 그래서 손님·사장 화면의 엄지 크기 컨트롤 대신
 * `.op-console` 아래에서 글자와 컨트롤을 한 단계 줄이고 표·카드를 옆으로 늘어놓는다.
 */
const menuGroups: { label: string; items: [string, string][] }[] = [
  { label: "운영", items: [["", "현황"], ["metrics", "사업 지표"], ["stores", "매장"], ["payments", "결제"], ["print-kits", "입점 키트"]] },
  { label: "시스템", items: [["accounts", "계정"], ["resources", "자원 현황"], ["audit", "활동 기록"], ["devices", "세션 보안"]] },
];

function MenuLinks({ pathname }: { pathname: string }) {
  return menuGroups.map((group) => (
    <div className="admin-nav-group" key={group.label}>
      <p>{group.label}</p>
      {group.items.map(([path, label]) => {
        const href = path ? `/operator/${path}` : "/operator";
        return (
          <Link key={label} href={href} aria-current={pathname === href || (path && pathname.startsWith(`${href}/`)) ? "page" : undefined}>
            {label}
          </Link>
        );
      })}
    </div>
  ));
}

export function OperatorFrame({ title, children }: { title: string; children: React.ReactNode }) {
  const pathname = usePathname();
  const [remaining, setRemaining] = useState<number>();

  useEffect(() => {
    const tick = () => {
      const expiresAt = sessionStorage.getItem("operatorExpiresAt");
      const seconds = expiresAt ? Math.max(0, Math.ceil((Date.parse(expiresAt) - Date.now()) / 1000)) : 0;
      setRemaining(seconds);
      if (seconds === 0) {
        clearAdminSession();
        window.location.replace("/operator/login");
      }
    };
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, []);

  return (
    <main className="admin-shell op-console">
      <aside className="admin-rail">
        <Link className="op-brand" href="/operator">
          <b>소담랩스</b>
          <small>운영자 콘솔</small>
        </Link>
        <nav className="admin-nav" aria-label="운영자 메뉴">
          <MenuLinks pathname={pathname} />
        </nav>
      </aside>

      <section className="admin-workspace">
        <header className="admin-head">
          <h1>{title}</h1>
          <div className="op-head-actions">
            {remaining !== undefined && (
              <span className="pill" data-tone={remaining <= 120 ? "bad" : "off"} aria-live="polite">
                세션 {Math.floor(remaining / 60)}:{String(remaining % 60).padStart(2, "0")}
              </span>
            )}
            <LogoutButton loginPath="/operator/login" />
          </div>
          <details className="admin-mobile-menu">
            <summary>전체 메뉴</summary>
            <nav aria-label="운영자 메뉴(모바일)">
              <MenuLinks pathname={pathname} />
            </nav>
          </details>
        </header>
        <div className="op-content">{children}</div>
      </section>
    </main>
  );
}
