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
      {/*
        ponytail: 화면별 탭 제목은 넣지 않는다. 이 화면들은 "use client"라 metadata를
        export할 수 없고, 두 가지 우회를 실제로 시도해 둘 다 실패했다.
        (1) document.title 직접 대입 — React 19가 <title>을 소유해 다음 렌더에 되돌린다.
        (2) JSX <title> — head로 올라가긴 하나 루트 metadata의 <title>이 앞서 이긴다.
        남은 방법은 화면마다 layout.tsx를 두는 것뿐인데, 파일 아홉 개를 늘릴 만한 값이 아니다.
        여러 탭을 동시에 여는 사용이 실제로 생기면 그때 layout.tsx를 추가한다.
      */}
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
