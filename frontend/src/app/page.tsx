import Link from "next/link";

export default function Home() {
  return (
    <main className="screen">
      <header className="stack">
        <p className="brand">윷 한 판, 오늘의 상품</p>
        <p className="result-mark" aria-hidden="true">
          윷
        </p>
        <h1>매장 QR로 참여해주세요</h1>
        <p className="lead">
          매장에 비치된 QR을 스캔하면 참여 화면이 열립니다. 이 주소로는 바로 참여할 수 없어요.
        </p>
      </header>
      <Link className="btn ghost" href="/admin/login">
        매장 관리자 로그인
      </Link>
    </main>
  );
}
