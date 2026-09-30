"use client";
import Image from "next/image";
import Link from "next/link";
import { useParams, usePathname } from "next/navigation";
import { LogoutButton } from "@/features/admin/LogoutButton";

const menuGroups = [
  { label: "운영", items: [["dashboard", "대시보드"], ["prizes", "상품 설정"], ["qr", "QR 안내물"]] },
  { label: "현장 관리", items: [["staff-pin", "직원 PIN"], ["game-plays", "참여 내역"], ["coupons", "쿠폰 내역"]] },
  { label: "분석", items: [["analytics", "통계"], ["ai", "AI 도우미"]] },
  { label: "계정", items: [["settings", "매장 정보"], ["plan", "요금제"]] },
] as const;

export function AdminFrame({ title, children }: { title: string; children: React.ReactNode }) {
  const id = String(useParams().storeId);
  const pathname = usePathname();

  return (
    <main className="admin-shell">
      {/*
        ponytail: 화면별 탭 제목은 넣지 않는다. 이 화면들은 "use client"라 metadata를
        export할 수 없고, 두 가지 우회를 실제로 시도해 둘 다 실패했다.
        (1) document.title 직접 대입 — React 19가 <title>을 소유해 다음 렌더에 되돌린다.
        (2) JSX <title> — head로 올라가긴 하나 루트 metadata의 <title>이 앞서 이긴다.
        남은 방법은 화면마다 layout.tsx를 두는 것뿐인데, 파일 아홉 개를 늘릴 만한 값이 아니다.
        여러 탭을 동시에 여는 사용이 실제로 생기면 그때 layout.tsx를 추가한다.
      */}
      <aside className="admin-rail">
        <Link className="admin-brand" href="/admin" aria-label="소담한판 매장 선택으로 이동">
          <Image src="/brand/sodam-wordmark.webp" width={1200} height={760} alt="소담" priority />
          <span><b>소담한판</b><small>매장 운영</small></span>
        </Link>
        <nav className="admin-nav" aria-label="매장 관리 메뉴">
          {menuGroups.map((group) => (
            <div className="admin-nav-group" key={group.label}>
              <p>{group.label}</p>
              {group.items.map(([path, label]) => {
                const href = `/admin/stores/${id}/${path}`;
                const current = pathname === href;
                return <Link key={path} href={href} aria-current={current ? "page" : undefined}>{label}</Link>;
              })}
            </div>
          ))}
        </nav>
        <div className="admin-rail-actions">
          <Link className="admin-text-action" href="/admin">매장 변경</Link>
          <Link className="admin-text-action" href="/admin/account">내 정보</Link>
          <LogoutButton />
        </div>
      </aside>
      <section className="admin-workspace">
        <header className="admin-head">
          <div>
            <p className="admin-kicker">매장 운영</p>
            <h1>{title}</h1>
          </div>
          <div className="admin-head-actions">
            <Link className="admin-text-action" href="/admin">매장 변경</Link>
            <Link className="admin-text-action" href="/admin/account">내 정보</Link>
            <LogoutButton />
          </div>
          <details className="admin-mobile-menu">
            <summary>전체 메뉴</summary>
            <nav aria-label="모바일 매장 관리 메뉴">
              {menuGroups.map((group) => (
                <div className="admin-mobile-nav-group" key={group.label}>
                  <p>{group.label}</p>
                  <div>
                    {group.items.map(([path, label]) => {
                      const href = `/admin/stores/${id}/${path}`;
                      const current = pathname === href;
                      return <Link key={path} href={href} aria-current={current ? "page" : undefined}>{label}</Link>;
                    })}
                  </div>
                </div>
              ))}
            </nav>
          </details>
        </header>
        <div className="admin-content">{children}</div>
      </section>
    </main>
  );
}
